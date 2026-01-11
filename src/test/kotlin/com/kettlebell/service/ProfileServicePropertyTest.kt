package com.kettlebell.service

import com.kettlebell.model.ExperienceLevel
import com.kettlebell.model.Gender
import com.kettlebell.model.ProfileData
import com.kettlebell.model.Subscription
import com.kettlebell.model.SubscriptionType
import com.kettlebell.model.UserMetadata
import com.kettlebell.model.UserProfile
import com.kettlebell.model.UserState
import com.kettlebell.repository.UserRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant

class ProfileServicePropertyTest : StringSpec({

    val userRepository = mockk<UserRepository>()
    val profileService = ProfileServiceImpl(userRepository)

    "Property 18: Equipment Weight Validation - should reject non-positive weights" {
        checkAll(100, Arb.list(Arb.int(Int.MIN_VALUE, 0))) { invalidWeights ->
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

            coEvery { userRepository.findById(userId) } returns profile

            if (invalidWeights.isNotEmpty() && invalidWeights.any { it <= 0 }) {
                shouldThrow<IllegalArgumentException> {
                    profileService.updateEquipment(userId, invalidWeights)
                }
            }
        }
    }

    "Property 18: Equipment Weight Validation - should accept positive weights" {
        checkAll(100, Arb.list(Arb.int(1, 100), 1..10)) { weights ->
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

            val updatedProfile =
                profile.copy(
                    profile = profile.profile.copy(weights = weights),
                )

            coEvery { userRepository.findById(userId) } returns profile
            coEvery { userRepository.save(any()) } returns updatedProfile

            val result = profileService.updateEquipment(userId, weights)

            result.profile.weights shouldBe weights
        }
    }

    "Property 1: Equipment Input Parsing - should handle various weight formats" {
        // This property test validates parsing logic
        // Since parsing happens in bot handler, we test the validation logic here
        checkAll(100, Arb.list(Arb.int(1, 100), 1..5)) { weights ->
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

            val updatedProfile =
                profile.copy(
                    profile = profile.profile.copy(weights = weights),
                )

            coEvery { userRepository.findById(userId) } returns profile
            coEvery { userRepository.save(any()) } returns updatedProfile

            val result = profileService.updateEquipment(userId, weights)

            result.profile.weights.size shouldBe weights.size
            result.profile.weights.all { it > 0 } shouldBe true
        }
    }

    "Property 2: Goal Storage - should persist goal updates" {
        checkAll(100, Arb.string(1..500)) { goal ->
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
                            goal = "old goal",
                        ),
                    subscription = Subscription(SubscriptionType.FREE, null),
                    metadata = UserMetadata(Instant.now(), Instant.now()),
                    schemaVersion = 1,
                )

            val updatedProfile =
                profile.copy(
                    profile = profile.profile.copy(goal = goal),
                )

            coEvery { userRepository.findById(userId) } returns profile
            coEvery { userRepository.save(any()) } returns updatedProfile

            val result = profileService.updateGoal(userId, goal)

            result.profile.goal shouldBe goal
        }
    }
})
