package com.kettlebell.model

import java.time.Instant
import java.util.UUID

// User Profile Models
data class UserProfile(
    val id: Long,
    val fsmState: UserState,
    val profile: ProfileData,
    val subscription: Subscription,
    val metadata: UserMetadata,
    val schemaVersion: Int = 1
)

data class ProfileData(
    val weights: List<Int>,
    val experience: ExperienceLevel,
    val bodyWeight: Float,
    val gender: Gender,
    val goal: String
)

enum class ExperienceLevel {
    BEGINNER, AMATEUR, PRO
}

enum class Gender {
    MALE, FEMALE, OTHER
}

data class Subscription(
    val type: SubscriptionType,
    val expiresAt: Instant?
)

enum class SubscriptionType {
    FREE, PREMIUM
}

data class UserMetadata(
    val createdAt: Instant,
    val lastActive: Instant
)

enum class UserState {
    IDLE,
    ONBOARDING_MEDICAL_CONFIRM,
    ONBOARDING_EQUIPMENT,
    ONBOARDING_EXPERIENCE,
    ONBOARDING_PERSONAL_DATA,
    ONBOARDING_GOALS,
    WORKOUT_REQUESTED,
    WORKOUT_IN_PROGRESS,
    WORKOUT_FEEDBACK_PENDING,
    EDIT_EQUIPMENT,
    EDIT_EXPERIENCE,
    EDIT_PERSONAL_DATA,
    EDIT_GOAL
}

// Workout Models
data class Workout(
    val id: String = UUID.randomUUID().toString(),
    val userId: Long,
    val status: WorkoutStatus,
    val plan: WorkoutPlan,
    val actualPerformance: ActualPerformance?,
    val timing: WorkoutTiming,
    val aiLog: AILog,
    val schemaVersion: Int = 1
)

enum class WorkoutStatus {
    PLANNED, IN_PROGRESS, COMPLETED, CANCELLED
}

data class WorkoutPlan(
    val warmup: String,
    val exercises: List<Exercise>,
    val cooldown: String,
    val aiLog: AILog? = null
)

data class Exercise(
    val name: String,
    val weight: Int,
    val reps: Int?,
    val sets: Int?,
    val timeWork: Int?,
    val timeRest: Int?,
    val coachingTips: String? = null
)

data class ActualPerformance(
    val rawFeedback: String,
    val data: List<ExercisePerformance>,
    val rpe: Int?,
    val issues: List<String> = emptyList(), // Maps to red_flags
    val recoveryStatus: String? = null,
    val technicalNotes: String? = null,
    val coachFeedback: String? = null,
    val aiLog: AILog? = null
)

data class ExercisePerformance(
    val name: String,
    val weight: Int,
    val reps: Int,
    val sets: Int,
    val completed: Boolean,
    val status: String? = null // completed, partial, failed
)

data class WorkoutTiming(
    val startedAt: Instant?,
    val completedAt: Instant?,
    val durationSeconds: Long?
)

data class AILog(
    val tokensUsed: Int,
    val modelVersion: String,
    val planGenerationTime: Long,
    val feedbackAnalysisTime: Long?,
    val finishReason: String?
)

// Context for AI
data class WorkoutContext(
    val profile: UserProfile,
    val recentWorkouts: List<Workout>,
    val availableWeights: List<Int>,
    val trainingWeek: Int,
    val suggestDeload: Boolean = false
)
