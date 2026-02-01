package com.kettlebell.service

import com.kettlebell.model.EventType
import com.kettlebell.model.UserState
import com.kettlebell.repository.UserRepository

class FSMManager(
    private val userRepository: UserRepository,
    private val analyticsService: AnalyticsService,
) {
    suspend fun getCurrentState(userId: Long): UserState {
        val profile = userRepository.findById(userId)
        return profile?.fsmState ?: UserState.IDLE
    }

    suspend fun transitionTo(
        userId: Long,
        newState: UserState,
    ) {
        val oldState = getCurrentState(userId)
        userRepository.updateState(userId, newState)

        if (oldState != newState) {
            analyticsService.track(
                userId,
                EventType.STATE_CHANGE,
                "${oldState.name} -> ${newState.name}",
                mapOf("from" to oldState.name, "to" to newState.name),
            )
        }
    }

    // Validates if transition is allowed
    fun canTransition(
        from: UserState,
        to: UserState,
    ): Boolean {
        return when (from) {
            UserState.IDLE ->
                to in
                    listOf(
                        UserState.ONBOARDING_MEDICAL_CONFIRM,
                        UserState.WORKOUT_REQUESTED,
                        UserState.EDIT_EQUIPMENT,
                        UserState.EDIT_EXPERIENCE,
                        UserState.EDIT_PERSONAL_DATA,
                        UserState.EDIT_GOAL,
                        UserState.SCHEDULING_DATE,
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
            UserState.EDIT_EQUIPMENT -> to == UserState.IDLE
            UserState.EDIT_EXPERIENCE -> to == UserState.IDLE
            UserState.EDIT_PERSONAL_DATA -> to == UserState.IDLE
            UserState.EDIT_GOAL -> to == UserState.IDLE

            // Scheduling
            UserState.SCHEDULING_DATE -> to == UserState.IDLE
        }
    }
}
