package com.kettlebell.service

import com.kettlebell.model.ExperienceLevel
import com.kettlebell.model.Gender
import com.kettlebell.model.ProfileData
import com.kettlebell.model.UserProfile

interface ProfileService {
    suspend fun createProfile(
        userId: Long,
        profileData: ProfileData,
    ): UserProfile

    suspend fun initProfile(userId: Long): UserProfile

    suspend fun getProfile(userId: Long): UserProfile?

    suspend fun updateEquipment(
        userId: Long,
        weights: List<Int>,
    ): UserProfile

    suspend fun updatePersonalData(
        userId: Long,
        bodyWeight: Float,
        gender: Gender,
    ): UserProfile

    suspend fun updateGoal(
        userId: Long,
        goal: String,
    ): UserProfile

    suspend fun updateExperience(
        userId: Long,
        experience: ExperienceLevel,
    ): UserProfile

    suspend fun updateScheduling(
        userId: Long,
        nextWorkout: java.time.Instant,
    ): UserProfile

    suspend fun clearScheduling(userId: Long): UserProfile

    suspend fun getUsersWithPendingReminders(): List<UserProfile>

    suspend fun markReminderSent(
        userId: Long,
        type: String,
    ): UserProfile
}
