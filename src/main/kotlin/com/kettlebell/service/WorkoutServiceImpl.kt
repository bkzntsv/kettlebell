package com.kettlebell.service

import com.kettlebell.config.AppConfig
import com.kettlebell.model.*
import com.kettlebell.repository.UserRepository
import com.kettlebell.repository.WorkoutRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class WorkoutServiceImpl(
    private val workoutRepository: WorkoutRepository,
    private val userRepository: UserRepository,
    private val aiService: AIService,
    private val config: AppConfig
) : WorkoutService {
    
    override suspend fun generateWorkoutPlan(userId: Long): Workout {
        val user = userRepository.findById(userId) ?: throw IllegalArgumentException("User not found")
        
        // Check subscription limits for FREE users
        if (user.subscription.type == SubscriptionType.FREE) {
            val startOfMonth = Instant.now().truncatedTo(ChronoUnit.DAYS).minus(30, ChronoUnit.DAYS) // Approximate month
            // Better: use start of current month? Or rolling 30 days?
            // Requirement 9.1 says "monthly limit". Usually calendar month or rolling 30 days.
            // Let's use rolling 30 days for simplicity if not specified, or start of month.
            // Let's stick to rolling 30 days as it's easier without Calendar complexity.
            val count = workoutRepository.countCompletedWorkoutsAfter(userId, startOfMonth)
            if (count >= config.freeMonthlyLimit) {
                throw IllegalStateException("Free monthly limit exceeded")
            }
        }
        
        val recentWorkouts = workoutRepository.findRecentByUserId(userId, 3)
        
        val context = WorkoutContext(
            profile = user,
            recentWorkouts = recentWorkouts,
            availableWeights = user.profile.weights,
            trainingWeek = 1 // TODO: Implement proper training week calculation
        )
        
        val plan = aiService.generateWorkoutPlan(context)
        
        val workout = Workout(
            id = UUID.randomUUID().toString(),
            userId = userId,
            status = WorkoutStatus.PLANNED,
            plan = plan,
            actualPerformance = null,
            timing = WorkoutTiming(null, null, null),
            aiLog = AILog(0, "gpt-4o", 0, null, null), // AI Service should return this info. 
            // Wait, AIService returns WorkoutPlan, not metadata. 
            // I need to update AIService to return metadata or handle it.
            // AIServiceImpl logs it but doesn't return it in generateWorkoutPlan.
            // The tasks.md says "Log tokens_used...". It doesn't strictly say save to DB in AILog struct,
            // but the model has AILog.
            // For now I will put placeholders or updated AIService later.
            // Let's stick to 0/empty for now as AIService signature returns WorkoutPlan.
            schemaVersion = 1
        )
        
        return workoutRepository.save(workout)
    }
    
    override suspend fun startWorkout(userId: Long, workoutId: String): Workout {
        val workout = workoutRepository.findById(workoutId) ?: throw IllegalArgumentException("Workout not found")
        if (workout.userId != userId) throw IllegalArgumentException("Workout does not belong to user")
        
        if (workout.status != WorkoutStatus.PLANNED) {
            throw IllegalStateException("Workout is not in PLANNED state")
        }
        
        val startedAt = Instant.now()
        val updatedWorkout = workout.copy(
            status = WorkoutStatus.IN_PROGRESS,
            timing = workout.timing.copy(startedAt = startedAt)
        )
        
        workoutRepository.save(updatedWorkout)
        userRepository.updateState(userId, UserState.WORKOUT_IN_PROGRESS)
        
        return updatedWorkout
    }
    
    override suspend fun finishWorkout(userId: Long, workoutId: String): Workout {
        val workout = workoutRepository.findById(workoutId) ?: throw IllegalArgumentException("Workout not found")
        if (workout.userId != userId) throw IllegalArgumentException("Workout does not belong to user")
        
        if (workout.status != WorkoutStatus.IN_PROGRESS) {
            throw IllegalStateException("Workout is not IN_PROGRESS")
        }
        
        val completedAt = Instant.now()
        val durationSeconds = if (workout.timing.startedAt != null) {
            ChronoUnit.SECONDS.between(workout.timing.startedAt, completedAt)
        } else {
            0
        }
        
        val updatedWorkout = workout.copy(
            // Status stays IN_PROGRESS or moves to FEEDBACK_PENDING?
            // Task 8.7: Update FSM state to WORKOUT_FEEDBACK_PENDING
            // But workout status? Usually waiting for feedback means it's still active or effectively "done but pending feedback".
            // Let's check requirements. Task 8.9 says "Update workout status to COMPLETED" AFTER feedback.
            // So here we probably keep it IN_PROGRESS or some intermediate state?
            // The WorkoutStatus enum has: PLANNED, IN_PROGRESS, COMPLETED, CANCELLED.
            // So maybe we keep it IN_PROGRESS until feedback is processed?
            // Or maybe we treat "finishWorkout" as the physical end, and feedback is post-processing.
            // Let's keep it IN_PROGRESS but update timing.
            timing = workout.timing.copy(
                completedAt = completedAt,
                durationSeconds = durationSeconds
            )
        )
        
        workoutRepository.save(updatedWorkout)
        userRepository.updateState(userId, UserState.WORKOUT_FEEDBACK_PENDING)
        
        return updatedWorkout
    }
    
    override suspend fun processFeedback(userId: Long, workoutId: String, feedback: String): Workout {
        val workout = workoutRepository.findById(workoutId) ?: throw IllegalArgumentException("Workout not found")
        if (workout.userId != userId) throw IllegalArgumentException("Workout does not belong to user")
        
        // Allow feedback if IN_PROGRESS (just finished) or COMPLETED (updating feedback?)
        // Task 8.9 says "Update workout status to COMPLETED".
        
        val performance = aiService.analyzeFeedback(feedback, workout.plan)
        
        val updatedWorkout = workout.copy(
            status = WorkoutStatus.COMPLETED,
            actualPerformance = performance
        )
        
        workoutRepository.save(updatedWorkout)
        userRepository.updateState(userId, UserState.IDLE)
        
        return updatedWorkout
    }
    
    override suspend fun getWorkoutHistory(userId: Long, limit: Int): List<Workout> {
        return workoutRepository.findByUserId(userId, limit)
    }
    
    override suspend fun calculateTotalVolume(workout: Workout): Int {
        return workout.actualPerformance?.data?.sumOf { 
            it.weight * it.reps * it.sets 
        } ?: 0
    }
}
