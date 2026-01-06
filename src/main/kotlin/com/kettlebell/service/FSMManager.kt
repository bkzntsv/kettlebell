package com.kettlebell.service

import com.kettlebell.model.UserState
import com.kettlebell.repository.UserRepository

class FSMManager(
    private val userRepository: UserRepository
) {
    suspend fun getCurrentState(userId: Long): UserState {
        val profile = userRepository.findById(userId)
        return profile?.fsmState ?: UserState.IDLE
    }
    
    suspend fun transitionTo(userId: Long, newState: UserState) {
        userRepository.updateState(userId, newState)
    }
}

