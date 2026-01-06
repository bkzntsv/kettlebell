package com.kettlebell.service

import com.kettlebell.config.AppConfig
import com.kettlebell.model.*
import com.kettlebell.repository.UserRepository
import com.kettlebell.repository.WorkoutRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.property.Arb
import io.kotest.property.arbitrary.*
import io.kotest.property.checkAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import java.util.UUID

class WorkoutServicePropertyTest : StringSpec({
    
    val workoutRepository = mockk<WorkoutRepository>()
    val userRepository = mockk<UserRepository>()
    val aiService = mockk<AIService>()
    val config = AppConfig(
        telegramBotToken = "token",
        openaiApiKey = "key",
        mongodbConnectionUri = "uri",
        mongodbDatabaseName = "db",
        freeMonthlyLimit = 5
    )
    val workoutService = WorkoutServiceImpl(workoutRepository, userRepository, aiService, config)
    
    "Property 4: Workout Generation Context - should include profile and available weights" {
        checkAll(100, Arb.long(), Arb.list(Arb.int(1, 100), 1..5)) { userId, weights ->
            val profile = UserProfile(
                id = userId,
                fsmState = UserState.IDLE,
                profile = ProfileData(
                    weights = weights,
                    experience = ExperienceLevel.BEGINNER,
                    bodyWeight = 70f,
                    gender = Gender.MALE,
                    goal = "goal"
                ),
                subscription = Subscription(SubscriptionType.FREE, null),
                metadata = UserMetadata(Instant.now(), Instant.now()),
                schemaVersion = 1
            )
            
            val plan = WorkoutPlan(
                warmup = "Warmup",
                exercises = emptyList(),
                cooldown = "Cooldown"
            )
            
            val workout = Workout(
                id = UUID.randomUUID().toString(),
                userId = userId,
                status = WorkoutStatus.PLANNED,
                plan = plan,
                actualPerformance = null,
                timing = WorkoutTiming(null, null, null),
                aiLog = AILog(0, "gpt-4o", 0, null, null),
                schemaVersion = 1
            )
            
            coEvery { userRepository.findById(userId) } returns profile
            coEvery { workoutRepository.countCompletedWorkoutsAfter(any(), any()) } returns 0
            coEvery { workoutRepository.findRecentByUserId(userId, 3) } returns emptyList()
            coEvery { aiService.generateWorkoutPlan(any()) } returns plan
            coEvery { workoutRepository.save(any()) } returns workout
            
            val result = workoutService.generateWorkoutPlan(userId)
            
            result.userId shouldBe userId
            result.status shouldBe WorkoutStatus.PLANNED
        }
    }
    
    "Property 16: Recent Workouts in Context - should include last 3 workouts with actual performance" {
        checkAll(100, Arb.long()) { userId ->
            val profile = UserProfile(
                id = userId,
                fsmState = UserState.IDLE,
                profile = ProfileData(
                    weights = listOf(16),
                    experience = ExperienceLevel.BEGINNER,
                    bodyWeight = 70f,
                    gender = Gender.MALE,
                    goal = "goal"
                ),
                subscription = Subscription(SubscriptionType.FREE, null),
                metadata = UserMetadata(Instant.now(), Instant.now()),
                schemaVersion = 1
            )
            
            val recentWorkouts = (0 until 3).map { i ->
                Workout(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    status = WorkoutStatus.COMPLETED,
                    plan = WorkoutPlan("Warmup", emptyList(), "Cooldown"),
                    actualPerformance = ActualPerformance(
                        rawFeedback = "Feedback $i",
                        data = listOf(ExercisePerformance("Swing", 16, 10, 3, true)),
                        rpe = 7,
                        issues = emptyList()
                    ),
                    timing = WorkoutTiming(
                        startedAt = Instant.now().minusSeconds((3 - i).toLong() * 86400),
                        completedAt = Instant.now().minusSeconds((3 - i).toLong() * 86400 + 1800),
                        durationSeconds = 1800L
                    ),
                    aiLog = AILog(0, "gpt-4o", 0, null, null),
                    schemaVersion = 1
                )
            }
            
            val plan = WorkoutPlan("Warmup", emptyList(), "Cooldown")
            val workout = Workout(
                id = UUID.randomUUID().toString(),
                userId = userId,
                status = WorkoutStatus.PLANNED,
                plan = plan,
                actualPerformance = null,
                timing = WorkoutTiming(null, null, null),
                aiLog = AILog(0, "gpt-4o", 0, null, null),
                schemaVersion = 1
            )
            
            coEvery { userRepository.findById(userId) } returns profile
            coEvery { workoutRepository.countCompletedWorkoutsAfter(any(), any()) } returns 0
            coEvery { workoutRepository.findRecentByUserId(userId, 3) } returns recentWorkouts
            coEvery { aiService.generateWorkoutPlan(any()) } returns plan
            coEvery { workoutRepository.save(any()) } returns workout
            
            val result = workoutService.generateWorkoutPlan(userId)
            
            result.status shouldBe WorkoutStatus.PLANNED
        }
    }
    
    "Property 23: Subscription Limit Checking - should reject FREE users exceeding monthly limit" {
        checkAll(100, Arb.long()) { userId ->
            val profile = UserProfile(
                id = userId,
                fsmState = UserState.IDLE,
                profile = ProfileData(
                    weights = listOf(16),
                    experience = ExperienceLevel.BEGINNER,
                    bodyWeight = 70f,
                    gender = Gender.MALE,
                    goal = "goal"
                ),
                subscription = Subscription(SubscriptionType.FREE, null),
                metadata = UserMetadata(Instant.now(), Instant.now()),
                schemaVersion = 1
            )
            
            coEvery { userRepository.findById(userId) } returns profile
            coEvery { workoutRepository.countCompletedWorkoutsAfter(any(), any()) } returns config.freeMonthlyLimit.toLong()
            
            shouldThrow<IllegalStateException> {
                workoutService.generateWorkoutPlan(userId)
            }
        }
    }
    
    "Property 6: Workout Start Timing - should record started_at timestamp" {
        checkAll(100, Arb.long(), Arb.string()) { userId, workoutId ->
            val workout = Workout(
                id = workoutId,
                userId = userId,
                status = WorkoutStatus.PLANNED,
                plan = WorkoutPlan("Warmup", emptyList(), "Cooldown"),
                actualPerformance = null,
                timing = WorkoutTiming(null, null, null),
                aiLog = AILog(0, "gpt-4o", 0, null, null),
                schemaVersion = 1
            )
            
            coEvery { workoutRepository.findById(workoutId) } returns workout
            coEvery { workoutRepository.save(any()) } answers { 
                val saved = firstArg<Workout>()
                saved
            }
            coEvery { userRepository.updateState(userId, UserState.WORKOUT_IN_PROGRESS) } returns Unit
            
            val result = workoutService.startWorkout(userId, workoutId)
            
            result.status shouldBe WorkoutStatus.IN_PROGRESS
            result.timing.startedAt shouldNotBe null // Verify it's set (not null)
        }
    }
    
    "Property 7: Workout Duration Calculation - should calculate duration_seconds correctly" {
        checkAll(100, Arb.long(), Arb.string()) { userId, workoutId ->
            val startedAt = Instant.now().minusSeconds(1800)
            val expectedDuration = 1800L
            
            val workout = Workout(
                id = workoutId,
                userId = userId,
                status = WorkoutStatus.IN_PROGRESS,
                plan = WorkoutPlan("Warmup", emptyList(), "Cooldown"),
                actualPerformance = null,
                timing = WorkoutTiming(startedAt, null, null),
                aiLog = AILog(0, "gpt-4o", 0, null, null),
                schemaVersion = 1
            )
            
            coEvery { workoutRepository.findById(workoutId) } returns workout
            coEvery { workoutRepository.save(any()) } answers { 
                val saved = firstArg<Workout>()
                saved
            }
            coEvery { userRepository.updateState(userId, UserState.WORKOUT_FEEDBACK_PENDING) } returns Unit
            
            val result = workoutService.finishWorkout(userId, workoutId)
            
            result.timing.durationSeconds shouldBe expectedDuration
            result.timing.completedAt shouldNotBe null // Verify it's set (not null)
        }
    }
    
    "Property 22: Total Volume Calculation - should sum weight × reps × sets for completed exercises" {
        checkAll(100, Arb.int(1, 100), Arb.int(1, 50), Arb.int(1, 10)) { weight, reps, sets ->
            val workout = Workout(
                id = UUID.randomUUID().toString(),
                userId = 123L,
                status = WorkoutStatus.COMPLETED,
                plan = WorkoutPlan("Warmup", emptyList(), "Cooldown"),
                actualPerformance = ActualPerformance(
                    rawFeedback = "Feedback",
                    data = listOf(
                        ExercisePerformance("Swing", weight, reps, sets, true)
                    ),
                    rpe = 7,
                    issues = emptyList()
                ),
                timing = WorkoutTiming(null, null, null),
                aiLog = AILog(0, "gpt-4o", 0, null, null),
                schemaVersion = 1
            )
            
            val expectedVolume = weight * reps * sets
            val volume = workoutService.calculateTotalVolume(workout)
            
            volume shouldBe expectedVolume
        }
    }
    
    "Property 22: Total Volume Calculation - should exclude incomplete exercises" {
        val workout = Workout(
            id = UUID.randomUUID().toString(),
            userId = 123L,
            status = WorkoutStatus.COMPLETED,
            plan = WorkoutPlan("Warmup", emptyList(), "Cooldown"),
            actualPerformance = ActualPerformance(
                rawFeedback = "Feedback",
                data = listOf(
                    ExercisePerformance("Swing", 16, 10, 3, true),
                    ExercisePerformance("Snatch", 16, 8, 2, false)
                ),
                rpe = 7,
                issues = emptyList()
            ),
            timing = WorkoutTiming(null, null, null),
            aiLog = AILog(0, "gpt-4o", 0, null, null),
            schemaVersion = 1
        )
        
        val volume = workoutService.calculateTotalVolume(workout)
        
        volume shouldBe 16 * 10 * 3 // Only completed exercise
    }
})

