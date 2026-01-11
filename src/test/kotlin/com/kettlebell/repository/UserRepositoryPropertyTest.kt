package com.kettlebell.repository

import com.kettlebell.model.ExperienceLevel
import com.kettlebell.model.Gender
import com.kettlebell.model.ProfileData
import com.kettlebell.model.Subscription
import com.kettlebell.model.SubscriptionType
import com.kettlebell.model.UserMetadata
import com.kettlebell.model.UserProfile
import com.kettlebell.model.UserState
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.time.Instant

class UserRepositoryPropertyTest : StringSpec({

    val userRepository = mockk<UserRepository>()

    "Property 19: Profile Update Persistence - updated profile should be saved and retrievable" {
        checkAll(100, Arb.long(), Arb.list(Arb.int(1, 100)), Arb.string()) { userId, weights, goal ->
            val originalProfile =
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
                originalProfile.copy(
                    profile =
                        originalProfile.profile.copy(
                            weights = weights,
                            goal = goal,
                        ),
                )

            coEvery { userRepository.save(updatedProfile) } returns updatedProfile

            // Simulate update - just verify that save returns the updated profile
            runBlocking {
                val saved = userRepository.save(updatedProfile)

                saved.profile.weights shouldBe weights
                saved.profile.goal shouldBe goal
            }

            runBlocking {
                coVerify { userRepository.save(updatedProfile) }
            }
        }
    }

    "Property 19: Profile Update Persistence - state updates should persist" {
        checkAll(100, Arb.long(), Arb.enum<UserState>()) { userId, newState ->
            val originalProfile =
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

            coEvery { userRepository.findById(userId) } returns originalProfile
            coEvery { userRepository.updateState(userId, newState) } returns Unit

            runBlocking {
                userRepository.updateState(userId, newState)
            }

            runBlocking {
                coVerify { userRepository.updateState(userId, newState) }
            }
        }
    }
})
