package com.kettlebell.service

import com.kettlebell.model.*
import com.kettlebell.repository.UserRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant

class ProfileServiceTest : StringSpec({
    
    val userRepository = mockk<UserRepository>()
    val profileService = ProfileServiceImpl(userRepository)
    
    "should create profile with valid data" {
        val userId = 123L
        val profileData = ProfileData(
            weights = listOf(16, 24),
            experience = ExperienceLevel.BEGINNER,
            bodyWeight = 70f,
            gender = Gender.MALE,
            goal = "Build strength"
        )
        
        val expectedProfile = UserProfile(
            id = userId,
            fsmState = UserState.IDLE,
            profile = profileData,
            subscription = Subscription(SubscriptionType.FREE, null),
            metadata = UserMetadata(Instant.now(), Instant.now()),
            schemaVersion = 1
        )
        
        coEvery { userRepository.save(any()) } returns expectedProfile
        
        val result = profileService.createProfile(userId, profileData)
        
        result.id shouldBe userId
        result.profile shouldBe profileData
        result.fsmState shouldBe UserState.IDLE
    }
    
    "should throw exception when updating equipment with empty weights" {
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
        
        shouldThrow<IllegalArgumentException> {
            profileService.updateEquipment(userId, emptyList())
        }
    }
    
    "should throw exception when updating equipment with negative weights" {
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
        
        shouldThrow<IllegalArgumentException> {
            profileService.updateEquipment(userId, listOf(-5, 16))
        }
    }
    
    "should update equipment with valid weights" {
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
        
        val updatedProfile = profile.copy(
            profile = profile.profile.copy(weights = listOf(16, 24, 32))
        )
        
        coEvery { userRepository.findById(userId) } returns profile
        coEvery { userRepository.save(any()) } returns updatedProfile
        
        val result = profileService.updateEquipment(userId, listOf(16, 24, 32))
        
        result.profile.weights shouldBe listOf(16, 24, 32)
    }
    
    "should update personal data" {
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
        
        val updatedProfile = profile.copy(
            profile = profile.profile.copy(bodyWeight = 75f, gender = Gender.FEMALE)
        )
        
        coEvery { userRepository.findById(userId) } returns profile
        coEvery { userRepository.save(any()) } returns updatedProfile
        
        val result = profileService.updatePersonalData(userId, 75f, Gender.FEMALE)
        
        result.profile.bodyWeight shouldBe 75f
        result.profile.gender shouldBe Gender.FEMALE
    }
    
    "should update goal" {
        val userId = 123L
        val profile = UserProfile(
            id = userId,
            fsmState = UserState.IDLE,
            profile = ProfileData(
                weights = listOf(16),
                experience = ExperienceLevel.BEGINNER,
                bodyWeight = 70f,
                gender = Gender.MALE,
                goal = "old goal"
            ),
            subscription = Subscription(SubscriptionType.FREE, null),
            metadata = UserMetadata(Instant.now(), Instant.now()),
            schemaVersion = 1
        )
        
        val updatedProfile = profile.copy(
            profile = profile.profile.copy(goal = "new goal")
        )
        
        coEvery { userRepository.findById(userId) } returns profile
        coEvery { userRepository.save(any()) } returns updatedProfile
        
        val result = profileService.updateGoal(userId, "new goal")
        
        result.profile.goal shouldBe "new goal"
    }
    
    "should update experience" {
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
        
        val updatedProfile = profile.copy(
            profile = profile.profile.copy(experience = ExperienceLevel.PRO)
        )
        
        coEvery { userRepository.findById(userId) } returns profile
        coEvery { userRepository.save(any()) } returns updatedProfile
        
        val result = profileService.updateExperience(userId, ExperienceLevel.PRO)
        
        result.profile.experience shouldBe ExperienceLevel.PRO
    }
    
    "should throw exception when profile not found" {
        val userId = 123L
        
        coEvery { userRepository.findById(userId) } returns null
        
        shouldThrow<IllegalStateException> {
            profileService.updateEquipment(userId, listOf(16))
        }
    }
})

