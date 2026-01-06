package com.kettlebell.repository

import com.kettlebell.model.UserProfile
import com.kettlebell.model.UserState
import com.kettlebell.model.Subscription

interface UserRepository {
    suspend fun findById(userId: Long): UserProfile?
    suspend fun save(profile: UserProfile): UserProfile
    suspend fun updateState(userId: Long, state: UserState)
    suspend fun updateSubscription(userId: Long, subscription: Subscription)
    suspend fun deleteById(userId: Long)
}

