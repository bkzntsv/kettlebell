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
    
    // Validates if transition is allowed
    fun canTransition(from: UserState, to: UserState): Boolean {
        return when (from) {
            UserState.IDLE -> to in listOf(
                UserState.ONBOARDING_MEDICAL_CONFIRM,
                UserState.WORKOUT_REQUESTED,
                UserState.PROFILE_EDITING
            )
            
            // Onboarding flow
            UserState.ONBOARDING_MEDICAL_CONFIRM -> to == UserState.ONBOARDING_EQUIPMENT
            UserState.ONBOARDING_EQUIPMENT -> to == UserState.ONBOARDING_EXPERIENCE
            UserState.ONBOARDING_EXPERIENCE -> to == UserState.ONBOARDING_PERSONAL_DATA
            UserState.ONBOARDING_PERSONAL_DATA -> to == UserState.ONBOARDING_GOALS
            UserState.ONBOARDING_GOALS -> to == UserState.IDLE
            
            // Workout flow
            UserState.WORKOUT_REQUESTED -> to in listOf(UserState.IDLE, UserState.WORKOUT_IN_PROGRESS)
            UserState.WORKOUT_IN_PROGRESS -> to == UserState.WORKOUT_FEEDBACK_PENDING
            UserState.WORKOUT_FEEDBACK_PENDING -> to == UserState.IDLE
            
            // Profile flow
            UserState.PROFILE_EDITING -> to == UserState.IDLE
        }
    }
}
