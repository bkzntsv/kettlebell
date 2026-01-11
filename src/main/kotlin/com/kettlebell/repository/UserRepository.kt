package com.kettlebell.repository

import com.kettlebell.model.Subscription
import com.kettlebell.model.UserProfile
import com.kettlebell.model.UserState

interface UserRepository {
    suspend fun findById(userId: Long): UserProfile?

    suspend fun save(profile: UserProfile): UserProfile

    suspend fun updateState(
        userId: Long,
        state: UserState,
    )

    suspend fun updateSubscription(
        userId: Long,
        subscription: Subscription,
    )

    suspend fun findUsersWithSchedule(): List<UserProfile>

    suspend fun deleteById(userId: Long)
}
