package com.kettlebell.service

import com.kettlebell.config.AppConfig
import com.kettlebell.error.AppError
import com.kettlebell.model.AILog
import com.kettlebell.model.SubscriptionType
import com.kettlebell.model.UserState
import com.kettlebell.model.Workout
import com.kettlebell.model.WorkoutContext
import com.kettlebell.model.WorkoutStatus
import com.kettlebell.model.WorkoutTiming
import com.kettlebell.repository.UserRepository
import com.kettlebell.repository.WorkoutRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class WorkoutServiceImpl(
    private val workoutRepository: WorkoutRepository,
    private val userRepository: UserRepository,
    private val aiService: AIService,
    private val config: AppConfig,
) : WorkoutService {
    override suspend fun generateWorkoutPlan(userId: Long): Workout {
        val user = userRepository.findById(userId) ?: throw IllegalArgumentException("User not found")

        if (user.profile.weights.isEmpty()) {
            throw IllegalArgumentException("В профиле не указаны гири. Пожалуйста, добавьте их через настройки (/profile).")
        }

        // Check subscription limits for FREE users
        if (user.subscription.type == SubscriptionType.FREE) {
            val startOfMonth = Instant.now().truncatedTo(ChronoUnit.DAYS).minus(30, ChronoUnit.DAYS) // Approximate month
            val count = workoutRepository.countCompletedWorkoutsAfter(userId, startOfMonth)
            if (count >= config.freeMonthlyLimit) {
                throw AppError.SubscriptionLimitExceeded()
            }
        }

        val recentWorkouts = workoutRepository.findRecentByUserId(userId, 3)

        val suggestDeload = shouldSuggestDeload(recentWorkouts)

        val context =
            WorkoutContext(
                profile = user,
                recentWorkouts = recentWorkouts,
                availableWeights = user.profile.weights,
                // TODO: Implement proper training week calculation
                trainingWeek = 1,
                suggestDeload = suggestDeload,
            )

        val plan = aiService.generateWorkoutPlan(context)

        // Use AI log from plan or default if missing
        val aiLog = plan.aiLog ?: AILog(0, "gpt-4o", 0, null, null)

        val workout =
            Workout(
                id = UUID.randomUUID().toString(),
                userId = userId,
                status = WorkoutStatus.PLANNED,
                plan = plan,
                actualPerformance = null,
                timing = WorkoutTiming(null, null, null),
                aiLog = aiLog,
                schemaVersion = 1,
            )

        return workoutRepository.save(workout)
    }

    override suspend fun startWorkout(
        userId: Long,
        workoutId: String,
    ): Workout {
        val workout = workoutRepository.findById(workoutId) ?: throw IllegalArgumentException("Workout not found")
        if (workout.userId != userId) throw IllegalArgumentException("Workout does not belong to user")

        if (workout.status != WorkoutStatus.PLANNED) {
            throw IllegalStateException("Workout is not in PLANNED state")
        }

        val startedAt = Instant.now()
        val updatedWorkout =
            workout.copy(
                status = WorkoutStatus.IN_PROGRESS,
                timing = workout.timing.copy(startedAt = startedAt),
            )

        workoutRepository.save(updatedWorkout)
        userRepository.updateState(userId, UserState.WORKOUT_IN_PROGRESS)

        return updatedWorkout
    }

    override suspend fun finishWorkout(
        userId: Long,
        workoutId: String,
    ): Workout {
        val workout = workoutRepository.findById(workoutId) ?: throw IllegalArgumentException("Workout not found")
        if (workout.userId != userId) throw IllegalArgumentException("Workout does not belong to user")

        if (workout.status != WorkoutStatus.IN_PROGRESS) {
            throw IllegalStateException("Workout is not IN_PROGRESS")
        }

        val completedAt = Instant.now()
        val durationSeconds =
            if (workout.timing.startedAt != null) {
                ChronoUnit.SECONDS.between(workout.timing.startedAt, completedAt)
            } else {
                0
            }

        val updatedWorkout =
            workout.copy(
                timing =
                    workout.timing.copy(
                        completedAt = completedAt,
                        durationSeconds = durationSeconds,
                    ),
            )

        workoutRepository.save(updatedWorkout)
        userRepository.updateState(userId, UserState.WORKOUT_FEEDBACK_PENDING)

        return updatedWorkout
    }

    override suspend fun processFeedback(
        userId: Long,
        workoutId: String,
        feedback: String,
    ): Workout {
        val workout = workoutRepository.findById(workoutId) ?: throw IllegalArgumentException("Workout not found")
        if (workout.userId != userId) throw IllegalArgumentException("Workout does not belong to user")

        val performance = aiService.analyzeFeedback(feedback, workout.plan)

        // Update AI log with feedback analysis stats
        val currentAiLog = workout.aiLog
        val newAiLog = performance.aiLog

        val updatedAiLog =
            if (newAiLog != null) {
                currentAiLog.copy(
                    tokensUsed = currentAiLog.tokensUsed + newAiLog.tokensUsed,
                    feedbackAnalysisTime = newAiLog.feedbackAnalysisTime,
                )
            } else {
                currentAiLog
            }

        val updatedWorkout =
            workout.copy(
                status = WorkoutStatus.COMPLETED,
                actualPerformance = performance,
                timing =
                    workout.timing.copy(
                        // Preserve calculated duration
                        durationSeconds = workout.timing.durationSeconds,
                    ),
                aiLog = updatedAiLog,
            )

        workoutRepository.save(updatedWorkout)
        userRepository.updateState(userId, UserState.IDLE)

        return updatedWorkout
    }

    override suspend fun getWorkoutHistory(
        userId: Long,
        limit: Int,
    ): List<Workout> {
        return workoutRepository.findByUserId(userId, limit)
    }

    override suspend fun calculateTotalVolume(workout: Workout): Int {
        val performance = workout.actualPerformance
        if (performance == null) {
            return 0
        }

        // Calculate volume for all exercises (completed, partial, or failed - as long as they have data)
        val volume =
            performance.data.sumOf { ex ->
                // Count volume if exercise has valid data (weight > 0, reps > 0, sets > 0)
                // We count partial completions too, not just fully completed
                if (ex.weight > 0 && ex.reps > 0 && ex.sets > 0) {
                    ex.weight * ex.reps * ex.sets
                } else {
                    0
                }
            }

        // Log details for debugging
        val logger = org.slf4j.LoggerFactory.getLogger(WorkoutServiceImpl::class.java)
        if (volume == 0 && performance.data.isNotEmpty()) {
            val details =
                performance.data.joinToString("; ") {
                    "${it.name}: weight=${it.weight}, reps=${it.reps}, sets=${it.sets}, completed=${it.completed}, status=${it.status}"
                }
            logger.warn("Total volume is 0 but exercises exist: $details")
        } else if (performance.data.isNotEmpty()) {
            logger.debug("Calculated total volume: $volume kg from ${performance.data.size} exercises")
        }

        return volume
    }

    private suspend fun shouldSuggestDeload(recentWorkouts: List<Workout>): Boolean {
        if (recentWorkouts.size < 3) return false

        // Check if last 3 workouts have high RPE (>8)
        val highRpeCount =
            recentWorkouts.take(3).count {
                val rpe = it.actualPerformance?.rpe ?: 0
                rpe > 8
            }

        if (highRpeCount < 3) return false

        // Check for volume stagnation
        // Simple logic: if volume hasn't increased in last 3 workouts
        val volumes = recentWorkouts.take(3).map { calculateTotalVolume(it) }
        val isStagnating = volumes.size == 3 && volumes[0] <= volumes[1] && volumes[1] <= volumes[2]

        return isStagnating
    }
}
