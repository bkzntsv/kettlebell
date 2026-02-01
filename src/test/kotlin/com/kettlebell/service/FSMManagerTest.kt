package com.kettlebell.service

import com.kettlebell.model.UserProfile
import com.kettlebell.model.UserState
import com.kettlebell.repository.UserRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk

class FSMManagerTest : StringSpec({
    val userRepository = mockk<UserRepository>()
    val analyticsService = mockk<AnalyticsService>(relaxed = true)
    val fsmManager = FSMManager(userRepository, analyticsService)

    "should allow valid onboarding flow transitions" {
        fsmManager.canTransition(UserState.IDLE, UserState.ONBOARDING_MEDICAL_CONFIRM) shouldBe true
        fsmManager.canTransition(UserState.ONBOARDING_MEDICAL_CONFIRM, UserState.ONBOARDING_EQUIPMENT) shouldBe true
        fsmManager.canTransition(UserState.ONBOARDING_EQUIPMENT, UserState.ONBOARDING_EXPERIENCE) shouldBe true
        fsmManager.canTransition(UserState.ONBOARDING_EXPERIENCE, UserState.ONBOARDING_PERSONAL_DATA) shouldBe true
        fsmManager.canTransition(UserState.ONBOARDING_PERSONAL_DATA, UserState.ONBOARDING_GOALS) shouldBe true
        fsmManager.canTransition(UserState.ONBOARDING_GOALS, UserState.IDLE) shouldBe true
    }

    "should allow valid workout flow transitions" {
        fsmManager.canTransition(UserState.IDLE, UserState.WORKOUT_REQUESTED) shouldBe true
        fsmManager.canTransition(UserState.WORKOUT_REQUESTED, UserState.WORKOUT_IN_PROGRESS) shouldBe true
        fsmManager.canTransition(UserState.WORKOUT_IN_PROGRESS, UserState.WORKOUT_FEEDBACK_PENDING) shouldBe true
        fsmManager.canTransition(UserState.WORKOUT_FEEDBACK_PENDING, UserState.IDLE) shouldBe true
    }

    "should allow cancellation of workout request" {
        fsmManager.canTransition(UserState.WORKOUT_REQUESTED, UserState.IDLE) shouldBe true
    }

    "should deny invalid transitions" {
        fsmManager.canTransition(UserState.IDLE, UserState.WORKOUT_IN_PROGRESS) shouldBe false
        fsmManager.canTransition(UserState.ONBOARDING_EQUIPMENT, UserState.IDLE) shouldBe false
        fsmManager.canTransition(UserState.WORKOUT_IN_PROGRESS, UserState.IDLE) shouldBe false
    }

    "getCurrentState should return IDLE if user profile not found" {
        coEvery { userRepository.findById(any()) } returns null
        fsmManager.getCurrentState(123L) shouldBe UserState.IDLE
    }

    "getCurrentState should return stored state" {
        val profile = mockk<UserProfile>()
        coEvery { profile.fsmState } returns UserState.WORKOUT_IN_PROGRESS
        coEvery { userRepository.findById(123L) } returns profile

        fsmManager.getCurrentState(123L) shouldBe UserState.WORKOUT_IN_PROGRESS
    }
})
