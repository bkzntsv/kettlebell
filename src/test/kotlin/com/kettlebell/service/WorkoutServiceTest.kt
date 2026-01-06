package com.kettlebell.service

import com.kettlebell.config.AppConfig
import com.kettlebell.model.*
import com.kettlebell.repository.UserRepository
import com.kettlebell.repository.WorkoutRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class WorkoutServiceTest : StringSpec({
    
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
    
    "should generate workout plan for new user with no history" {
        val userId = 123L
        val profile = UserProfile(
            id = userId,
            fsmState = UserState.IDLE,
            profile = ProfileData(
                weights = listOf(16, 24),
                experience = ExperienceLevel.BEGINNER,
                bodyWeight = 70f,
                gender = Gender.MALE,
                goal = "Build strength"
            ),
            subscription = Subscription(SubscriptionType.FREE, null),
            metadata = UserMetadata(Instant.now(), Instant.now()),
            schemaVersion = 1
        )
        
        val plan = WorkoutPlan(
            warmup = "Warmup",
            exercises = listOf(Exercise("Swing", 16, 10, 3, null, null)),
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
        result.plan shouldBe plan
    }
    
    "should throw exception when user not found" {
        val userId = 123L
        
        coEvery { userRepository.findById(userId) } returns null
        
        shouldThrow<IllegalArgumentException> {
            workoutService.generateWorkoutPlan(userId)
        }
    }
    
    "should throw exception when user has no equipment" {
        val userId = 123L
        val profile = UserProfile(
            id = userId,
            fsmState = UserState.IDLE,
            profile = ProfileData(
                weights = emptyList(),
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
        
        shouldThrow<IllegalArgumentException> {
            workoutService.generateWorkoutPlan(userId)
        }
    }
    
    "should throw exception when FREE user exceeds monthly limit" {
        val userId = 123L
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
    
    "should start workout and update state" {
        val userId = 123L
        val workoutId = UUID.randomUUID().toString()
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
    
    "should throw exception when starting workout not in PLANNED state" {
        val userId = 123L
        val workoutId = UUID.randomUUID().toString()
        val workout = Workout(
            id = workoutId,
            userId = userId,
            status = WorkoutStatus.IN_PROGRESS,
            plan = WorkoutPlan("Warmup", emptyList(), "Cooldown"),
            actualPerformance = null,
            timing = WorkoutTiming(null, null, null),
            aiLog = AILog(0, "gpt-4o", 0, null, null),
            schemaVersion = 1
        )
        
        coEvery { workoutRepository.findById(workoutId) } returns workout
        
        shouldThrow<IllegalStateException> {
            workoutService.startWorkout(userId, workoutId)
        }
    }
    
    "should finish workout and calculate duration" {
        val userId = 123L
        val workoutId = UUID.randomUUID().toString()
        val startedAt = Instant.now().minusSeconds(1800)
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
        
        result.timing.completedAt shouldNotBe null // Verify it's set (not null)
        result.timing.durationSeconds shouldBe 1800
    }
    
    "should process feedback and update workout status" {
        val userId = 123L
        val workoutId = UUID.randomUUID().toString()
        val workout = Workout(
            id = workoutId,
            userId = userId,
            status = WorkoutStatus.PLANNED,
            plan = WorkoutPlan(
                "Warmup",
                listOf(Exercise("Swing", 16, 10, 3, null, null)),
                "Cooldown"
            ),
            actualPerformance = null,
            timing = WorkoutTiming(null, null, null),
            aiLog = AILog(0, "gpt-4o", 0, null, null),
            schemaVersion = 1
        )
        
        val performance = ActualPerformance(
            rawFeedback = "Completed all exercises",
            data = listOf(ExercisePerformance("Swing", 16, 10, 3, true)),
            rpe = 7,
            issues = emptyList()
        )
        
        val updatedWorkout = workout.copy(
            status = WorkoutStatus.COMPLETED,
            actualPerformance = performance
        )
        
        coEvery { workoutRepository.findById(workoutId) } returns workout
        coEvery { aiService.analyzeFeedback(any(), any()) } returns performance
        coEvery { workoutRepository.save(any()) } returns updatedWorkout
        coEvery { userRepository.updateState(userId, UserState.IDLE) } returns Unit
        
        val result = workoutService.processFeedback(userId, workoutId, "Completed all exercises")
        
        result.status shouldBe WorkoutStatus.COMPLETED
        result.actualPerformance shouldBe performance
    }
    
    "should get workout history" {
        val userId = 123L
        val workouts = (0 until 5).map { i ->
            Workout(
                id = UUID.randomUUID().toString(),
                userId = userId,
                status = WorkoutStatus.COMPLETED,
                plan = WorkoutPlan("Warmup", emptyList(), "Cooldown"),
                actualPerformance = null,
                timing = WorkoutTiming(
                    startedAt = Instant.now().minusSeconds((5 - i).toLong() * 86400),
                    completedAt = Instant.now().minusSeconds((5 - i).toLong() * 86400 + 1800),
                    durationSeconds = 1800L
                ),
                aiLog = AILog(0, "gpt-4o", 0, null, null),
                schemaVersion = 1
            )
        }
        
        coEvery { workoutRepository.findByUserId(userId, 10) } returns workouts
        
        val history = workoutService.getWorkoutHistory(userId, 10)
        
        history.size shouldBe 5
        history.all { it.userId == userId } shouldBe true
    }
    
    "should calculate total volume correctly" {
        val workout = Workout(
            id = UUID.randomUUID().toString(),
            userId = 123L,
            status = WorkoutStatus.COMPLETED,
            plan = WorkoutPlan("Warmup", emptyList(), "Cooldown"),
            actualPerformance = ActualPerformance(
                rawFeedback = "Feedback",
                data = listOf(
                    ExercisePerformance("Swing", 16, 10, 3, true),
                    ExercisePerformance("Snatch", 24, 8, 2, true)
                ),
                rpe = 7,
                issues = emptyList()
            ),
            timing = WorkoutTiming(null, null, null),
            aiLog = AILog(0, "gpt-4o", 0, null, null),
            schemaVersion = 1
        )
        
        val volume = workoutService.calculateTotalVolume(workout)
        
        volume shouldBe (16 * 10 * 3 + 24 * 8 * 2)
    }
    
    "should return zero volume for workout without actual performance" {
        val workout = Workout(
            id = UUID.randomUUID().toString(),
            userId = 123L,
            status = WorkoutStatus.PLANNED,
            plan = WorkoutPlan("Warmup", emptyList(), "Cooldown"),
            actualPerformance = null,
            timing = WorkoutTiming(null, null, null),
            aiLog = AILog(0, "gpt-4o", 0, null, null),
            schemaVersion = 1
        )
        
        val volume = workoutService.calculateTotalVolume(workout)
        
        volume shouldBe 0
    }
})

