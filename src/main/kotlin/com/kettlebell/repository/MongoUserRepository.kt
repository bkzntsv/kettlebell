package com.kettlebell.repository

import com.kettlebell.model.UserProfile
import com.kettlebell.model.UserState
import com.kettlebell.model.Subscription
import org.litote.kmongo.coroutine.CoroutineDatabase
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.eq
import org.litote.kmongo.setValue

class MongoUserRepository(
    private val database: CoroutineDatabase
) : UserRepository {
    private val collection: CoroutineCollection<UserProfile> = 
        database.getCollection<UserProfile>("users")
    
    override suspend fun findById(userId: Long): UserProfile? {
        return collection.findOne(UserProfile::id eq userId)
    }
    
    override suspend fun save(profile: UserProfile): UserProfile {
        collection.save(profile)
        return profile
    }
    
    override suspend fun updateState(userId: Long, state: UserState) {
        collection.updateOne(
            UserProfile::id eq userId,
            setValue(UserProfile::fsmState, state)
        )
    }
    
    override suspend fun updateSubscription(userId: Long, subscription: Subscription) {
        collection.updateOne(
            UserProfile::id eq userId,
            setValue(UserProfile::subscription, subscription)
        )
    }
}

