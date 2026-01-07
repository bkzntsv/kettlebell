package com.kettlebell.service

import com.kettlebell.model.*
import com.kettlebell.repository.UserRepository
import java.time.Instant

class ProfileServiceImpl(
    private val userRepository: UserRepository
) : ProfileService {
    override suspend fun createProfile(userId: Long, profileData: ProfileData): UserProfile {
        validateWeights(profileData.weights)
        val profile = UserProfile(
            id = userId,
            fsmState = UserState.IDLE,
            profile = profileData,
            subscription = Subscription(SubscriptionType.FREE, null),
            metadata = UserMetadata(Instant.now(), Instant.now()),
            schemaVersion = 1
        )
        return userRepository.save(profile)
    }
    
    override suspend fun initProfile(userId: Long): UserProfile {
        // Delete any existing profile to ensure clean state and remove duplicates
        userRepository.deleteById(userId)
        
        val initialProfileData = ProfileData(
            weights = emptyList(),
            experience = ExperienceLevel.BEGINNER,
            bodyWeight = 0f,
            gender = Gender.OTHER,
            goal = ""
        )
        
        val profile = UserProfile(
            id = userId,
            fsmState = UserState.IDLE,
            profile = initialProfileData,
            subscription = Subscription(SubscriptionType.FREE, null),
            metadata = UserMetadata(Instant.now(), Instant.now()),
            schemaVersion = 1
        )
        return userRepository.save(profile)
    }
    
    override suspend fun getProfile(userId: Long): UserProfile? {
        return userRepository.findById(userId)
    }
    
    override suspend fun updateEquipment(userId: Long, weights: List<Int>): UserProfile {
        validateWeights(weights)
        val profile = userRepository.findById(userId) ?: throw IllegalStateException("Profile not found")
        val updatedProfile = profile.copy(
            profile = profile.profile.copy(weights = weights)
        )
        return userRepository.save(updatedProfile)
    }
    
    private fun validateWeights(weights: List<Int>) {
        require(weights.isNotEmpty()) { "At least one kettlebell weight must be provided" }
        require(weights.all { it > 0 }) { "All weights must be positive integers" }
    }
    
    override suspend fun updatePersonalData(userId: Long, bodyWeight: Float, gender: Gender): UserProfile {
        val profile = userRepository.findById(userId) ?: throw IllegalStateException("Profile not found")
        val updatedProfile = profile.copy(
            profile = profile.profile.copy(bodyWeight = bodyWeight, gender = gender)
        )
        return userRepository.save(updatedProfile)
    }
    
    override suspend fun updateGoal(userId: Long, goal: String): UserProfile {
        val profile = userRepository.findById(userId) ?: throw IllegalStateException("Profile not found")
        val updatedProfile = profile.copy(
            profile = profile.profile.copy(goal = goal)
        )
        return userRepository.save(updatedProfile)
    }
    
    override suspend fun updateExperience(userId: Long, experience: ExperienceLevel): UserProfile {
        val profile = userRepository.findById(userId) ?: throw IllegalStateException("Profile not found")
        val updatedProfile = profile.copy(
            profile = profile.profile.copy(experience = experience)
        )
        return userRepository.save(updatedProfile)
    }

    override suspend fun updateScheduling(userId: Long, nextWorkout: Instant): UserProfile {
        val profile = userRepository.findById(userId) ?: throw IllegalStateException("Profile not found")
        val updatedProfile = profile.copy(
            scheduling = UserScheduling(nextWorkout = nextWorkout)
        )
        return userRepository.save(updatedProfile)
    }

    override suspend fun clearScheduling(userId: Long): UserProfile {
        val profile = userRepository.findById(userId) ?: throw IllegalStateException("Profile not found")
        val updatedProfile = profile.copy(
            scheduling = null
        )
        return userRepository.save(updatedProfile)
    }

    override suspend fun getUsersWithPendingReminders(): List<UserProfile> {
        return userRepository.findUsersWithSchedule()
    }

    override suspend fun markReminderSent(userId: Long, type: String): UserProfile {
        val profile = userRepository.findById(userId) ?: throw IllegalStateException("Profile not found")
        val currentScheduling = profile.scheduling ?: return profile

        val updatedScheduling = when (type) {
            "1h" -> currentScheduling.copy(reminder1hSent = true)
            "5m" -> currentScheduling.copy(reminder5mSent = true)
            else -> currentScheduling
        }

        return userRepository.save(profile.copy(scheduling = updatedScheduling))
    }
}

