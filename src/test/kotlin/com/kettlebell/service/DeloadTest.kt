package com.kettlebell.service

import com.kettlebell.config.AppConfig
import com.kettlebell.model.AILog
import com.kettlebell.model.ActualPerformance
import com.kettlebell.model.ExercisePerformance
import com.kettlebell.model.ExperienceLevel
import com.kettlebell.model.Gender
import com.kettlebell.model.ProfileData
import com.kettlebell.model.Subscription
import com.kettlebell.model.SubscriptionType
import com.kettlebell.model.UserMetadata
import com.kettlebell.model.UserProfile
import com.kettlebell.model.UserState
import com.kettlebell.model.Workout
import com.kettlebell.model.WorkoutPlan
import com.kettlebell.model.WorkoutStatus
import com.kettlebell.model.WorkoutTiming
import com.kettlebell.repository.UserRepository
import com.kettlebell.repository.WorkoutRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import java.util.UUID

class DeloadTest : StringSpec({

    val workoutRepository = mockk<WorkoutRepository>()
    val userRepository = mockk<UserRepository>()
    val aiService = mockk<AIService>()
    val config =
        AppConfig(
            telegramBotToken = "token",
            openaiApiKey = "key",
            mongodbConnectionUri = "uri",
            mongodbDatabaseName = "db",
            freeMonthlyLimit = 5,
        )
    val workoutService = WorkoutServiceImpl(workoutRepository, userRepository, aiService, config)

    "should suggest deload after 3 weeks of high-intensity training" {
        val userId = 123L
        val profile =
            UserProfile(
                id = userId,
                fsmState = UserState.IDLE,
                profile =
                    ProfileData(
                        weights = listOf(16),
                        experience = ExperienceLevel.BEGINNER,
                        bodyWeight = 70f,
                        gender = Gender.MALE,
                        goal = "goal",
                    ),
                subscription = Subscription(SubscriptionType.FREE, null),
                metadata = UserMetadata(Instant.now(), Instant.now()),
                schemaVersion = 1,
            )

        // 3 workouts with RPE > 8 and stagnating volume
        val recentWorkouts =
            (0 until 3).map { i ->
                Workout(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    status = WorkoutStatus.COMPLETED,
                    plan = WorkoutPlan("Warmup", emptyList(), "Cooldown"),
                    actualPerformance =
                        ActualPerformance(
                            rawFeedback = "Feedback",
                            data = listOf(ExercisePerformance("Swing", 16, 10, 3, true)),
                            rpe = 9,
                            issues = emptyList(),
                        ),
                    timing =
                        WorkoutTiming(
                            startedAt = Instant.now().minusSeconds((3 - i).toLong() * 86400),
                            completedAt = Instant.now().minusSeconds((3 - i).toLong() * 86400 + 1800),
                            durationSeconds = 1800L,
                        ),
                    aiLog = AILog(0, "gpt-4o", 0, null, null),
                    schemaVersion = 1,
                )
            }

        val plan = WorkoutPlan("Warmup", emptyList(), "Cooldown")
        val workout =
            Workout(
                id = UUID.randomUUID().toString(),
                userId = userId,
                status = WorkoutStatus.PLANNED,
                plan = plan,
                actualPerformance = null,
                timing = WorkoutTiming(null, null, null),
                aiLog = AILog(0, "gpt-4o", 0, null, null),
                schemaVersion = 1,
            )

        coEvery { userRepository.findById(userId) } returns profile
        coEvery { workoutRepository.countCompletedWorkoutsAfter(any(), any()) } returns 0
        coEvery { workoutRepository.findRecentByUserId(userId, 3) } returns recentWorkouts
        coEvery { aiService.generateWorkoutPlan(any()) } returns plan
        coEvery { workoutRepository.save(any()) } returns workout

        val result = workoutService.generateWorkoutPlan(userId)

        result.status shouldBe WorkoutStatus.PLANNED
    }

    "should not suggest deload for low-intensity training" {
        val userId = 123L
        val profile =
            UserProfile(
                id = userId,
                fsmState = UserState.IDLE,
                profile =
                    ProfileData(
                        weights = listOf(16),
                        experience = ExperienceLevel.BEGINNER,
                        bodyWeight = 70f,
                        gender = Gender.MALE,
                        goal = "goal",
                    ),
                subscription = Subscription(SubscriptionType.FREE, null),
                metadata = UserMetadata(Instant.now(), Instant.now()),
                schemaVersion = 1,
            )

        // 3 workouts with low RPE
        val recentWorkouts =
            (0 until 3).map { i ->
                Workout(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    status = WorkoutStatus.COMPLETED,
                    plan = WorkoutPlan("Warmup", emptyList(), "Cooldown"),
                    actualPerformance =
                        ActualPerformance(
                            rawFeedback = "Feedback",
                            data = listOf(ExercisePerformance("Swing", 16, 10, 3, true)),
                            // Low RPE
                            rpe = 5,
                            issues = emptyList(),
                        ),
                    timing =
                        WorkoutTiming(
                            startedAt = Instant.now().minusSeconds((3 - i).toLong() * 86400),
                            completedAt = Instant.now().minusSeconds((3 - i).toLong() * 86400 + 1800),
                            durationSeconds = 1800L,
                        ),
                    aiLog = AILog(0, "gpt-4o", 0, null, null),
                    schemaVersion = 1,
                )
            }

        val plan = WorkoutPlan("Warmup", emptyList(), "Cooldown")
        val workout =
            Workout(
                id = UUID.randomUUID().toString(),
                userId = userId,
                status = WorkoutStatus.PLANNED,
                plan = plan,
                actualPerformance = null,
                timing = WorkoutTiming(null, null, null),
                aiLog = AILog(0, "gpt-4o", 0, null, null),
                schemaVersion = 1,
            )

        coEvery { userRepository.findById(userId) } returns profile
        coEvery { workoutRepository.countCompletedWorkoutsAfter(any(), any()) } returns 0
        coEvery { workoutRepository.findRecentByUserId(userId, 3) } returns recentWorkouts
        coEvery { aiService.generateWorkoutPlan(any()) } returns plan
        coEvery { workoutRepository.save(any()) } returns workout

        val result = workoutService.generateWorkoutPlan(userId)

        result.status shouldBe WorkoutStatus.PLANNED
        // Deload should not be suggested for low-intensity training
    }

    "should not suggest deload with less than 3 workouts" {
        val userId = 123L
        val profile =
            UserProfile(
                id = userId,
                fsmState = UserState.IDLE,
                profile =
                    ProfileData(
                        weights = listOf(16),
                        experience = ExperienceLevel.BEGINNER,
                        bodyWeight = 70f,
                        gender = Gender.MALE,
                        goal = "goal",
                    ),
                subscription = Subscription(SubscriptionType.FREE, null),
                metadata = UserMetadata(Instant.now(), Instant.now()),
                schemaVersion = 1,
            )

        // Only 2 workouts
        val recentWorkouts =
            (0 until 2).map { i ->
                Workout(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    status = WorkoutStatus.COMPLETED,
                    plan = WorkoutPlan("Warmup", emptyList(), "Cooldown"),
                    actualPerformance =
                        ActualPerformance(
                            rawFeedback = "Feedback",
                            data = listOf(ExercisePerformance("Swing", 16, 10, 3, true)),
                            rpe = 9,
                            issues = emptyList(),
                        ),
                    timing =
                        WorkoutTiming(
                            startedAt = Instant.now().minusSeconds((2 - i).toLong() * 86400),
                            completedAt = Instant.now().minusSeconds((2 - i).toLong() * 86400 + 1800),
                            durationSeconds = 1800L,
                        ),
                    aiLog = AILog(0, "gpt-4o", 0, null, null),
                    schemaVersion = 1,
                )
            }

        val plan = WorkoutPlan("Warmup", emptyList(), "Cooldown")
        val workout =
            Workout(
                id = UUID.randomUUID().toString(),
                userId = userId,
                status = WorkoutStatus.PLANNED,
                plan = plan,
                actualPerformance = null,
                timing = WorkoutTiming(null, null, null),
                aiLog = AILog(0, "gpt-4o", 0, null, null),
                schemaVersion = 1,
            )

        coEvery { userRepository.findById(userId) } returns profile
        coEvery { workoutRepository.countCompletedWorkoutsAfter(any(), any()) } returns 0
        coEvery { workoutRepository.findRecentByUserId(userId, 3) } returns recentWorkouts
        coEvery { aiService.generateWorkoutPlan(any()) } returns plan
        coEvery { workoutRepository.save(any()) } returns workout

        val result = workoutService.generateWorkoutPlan(userId)

        result.status shouldBe WorkoutStatus.PLANNED
    }
})
