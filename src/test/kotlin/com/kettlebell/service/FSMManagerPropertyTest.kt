package com.kettlebell.service

import com.kettlebell.model.ExperienceLevel
import com.kettlebell.model.Gender
import com.kettlebell.model.ProfileData
import com.kettlebell.model.Subscription
import com.kettlebell.model.SubscriptionType
import com.kettlebell.model.TrainingGoal
import com.kettlebell.model.UserMetadata
import com.kettlebell.model.UserProfile
import com.kettlebell.model.UserState
import com.kettlebell.repository.UserRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.time.Instant

class FSMManagerPropertyTest : StringSpec({

    val userRepository = mockk<UserRepository>()
    val analyticsService = mockk<AnalyticsService>(relaxed = true)
    val fsmManager = FSMManager(userRepository, analyticsService)

    "Property 8: FSM State Transition on Workout Completion - should transition from WORKOUT_IN_PROGRESS to WORKOUT_FEEDBACK_PENDING" {
        val userId = 123L
        val profile =
            UserProfile(
                id = userId,
                fsmState = UserState.WORKOUT_IN_PROGRESS,
                profile =
                    ProfileData(
                        weights = listOf(16),
                        experience = ExperienceLevel.BEGINNER,
                        bodyWeight = 70f,
                        gender = Gender.MALE,
                        goal = TrainingGoal.GENERAL_FITNESS,
                    ),
                subscription = Subscription(SubscriptionType.FREE, null),
                metadata = UserMetadata(Instant.now(), Instant.now()),
                schemaVersion = 1,
            )

        coEvery { userRepository.findById(userId) } returns profile
        coEvery { userRepository.updateState(userId, UserState.WORKOUT_FEEDBACK_PENDING) } returns Unit

        val canTransition = fsmManager.canTransition(UserState.WORKOUT_IN_PROGRESS, UserState.WORKOUT_FEEDBACK_PENDING)
        canTransition shouldBe true

        runBlocking {
            fsmManager.transitionTo(userId, UserState.WORKOUT_FEEDBACK_PENDING)
            coVerify { userRepository.updateState(userId, UserState.WORKOUT_FEEDBACK_PENDING) }
        }
    }

    "Property 8: FSM State Transition on Workout Completion - should transition from WORKOUT_FEEDBACK_PENDING to IDLE after feedback" {
        val userId = 123L
        val profile =
            UserProfile(
                id = userId,
                fsmState = UserState.WORKOUT_FEEDBACK_PENDING,
                profile =
                    ProfileData(
                        weights = listOf(16),
                        experience = ExperienceLevel.BEGINNER,
                        bodyWeight = 70f,
                        gender = Gender.MALE,
                        goal = TrainingGoal.GENERAL_FITNESS,
                    ),
                subscription = Subscription(SubscriptionType.FREE, null),
                metadata = UserMetadata(Instant.now(), Instant.now()),
                schemaVersion = 1,
            )

        coEvery { userRepository.findById(userId) } returns profile
        coEvery { userRepository.updateState(userId, UserState.IDLE) } returns Unit

        val canTransition = fsmManager.canTransition(UserState.WORKOUT_FEEDBACK_PENDING, UserState.IDLE)
        canTransition shouldBe true

        runBlocking {
            fsmManager.transitionTo(userId, UserState.IDLE)
            coVerify { userRepository.updateState(userId, UserState.IDLE) }
        }
    }
})
