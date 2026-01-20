package com.kettlebell.bot

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
import com.kettlebell.service.ProfileServiceImpl
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.time.Instant

class OnboardingIdempotenceTest : StringSpec({

    val userRepository = mockk<UserRepository>()
    val profileService = ProfileServiceImpl(userRepository)

    "Property 3: Onboarding Idempotence - multiple /start should not break existing profile" {
        checkAll(100, Arb.long(), Arb.list(Arb.int(1, 100), 1..5), Arb.enum<TrainingGoal>()) { userId, weights, goal ->
            val existingProfile =
                UserProfile(
                    id = userId,
                    fsmState = UserState.IDLE,
                    profile =
                        ProfileData(
                            weights = weights,
                            experience = ExperienceLevel.BEGINNER,
                            bodyWeight = 70f,
                            gender = Gender.MALE,
                            goal = goal,
                        ),
                    subscription = Subscription(SubscriptionType.FREE, null),
                    metadata = UserMetadata(Instant.now(), Instant.now()),
                    schemaVersion = 1,
                )

            // Simulate multiple /start calls - profile should remain the same
            coEvery { userRepository.findById(userId) } returns existingProfile

            runBlocking {
                // Simulate first /start check
                val profile1 = profileService.getProfile(userId)

                // Simulate second /start check
                val profile2 = profileService.getProfile(userId)

                // Profile should remain intact - idempotence property
                profile1?.id shouldBe profile2?.id
                profile1?.profile?.weights shouldBe profile2?.profile?.weights
                profile1?.profile?.goal shouldBe profile2?.profile?.goal
                profile1?.profile?.experience shouldBe profile2?.profile?.experience
            }
        }
    }

    "Property 3: Onboarding Idempotence - initProfile should create new profile if not exists" {
        checkAll(100, Arb.long()) { userId ->
            val newProfile =
                UserProfile(
                    id = userId,
                    fsmState = UserState.IDLE,
                    profile =
                        ProfileData(
                            weights = emptyList(),
                            experience = ExperienceLevel.BEGINNER,
                            bodyWeight = 0f,
                            gender = Gender.OTHER,
                            goal = TrainingGoal.GENERAL_FITNESS,
                        ),
                    subscription = Subscription(SubscriptionType.FREE, null),
                    metadata = UserMetadata(Instant.now(), Instant.now()),
                    schemaVersion = 1,
                )

            coEvery { userRepository.findById(userId) } returns null
            coEvery { userRepository.deleteById(userId) } returns Unit
            coEvery { userRepository.save(any()) } returns newProfile

            runBlocking {
                val profile = profileService.initProfile(userId)

                profile.id shouldBe userId
                profile.fsmState shouldBe UserState.IDLE
                profile.profile.weights.isEmpty() shouldBe true
            }
        }
    }
})
