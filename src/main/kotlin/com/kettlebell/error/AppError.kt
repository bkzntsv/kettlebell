package com.kettlebell.error

sealed class AppError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    // AI Service Errors
    class AIServiceUnavailable(cause: Throwable? = null) : AppError("AI service is currently unavailable", cause)
    class TranscriptionFailed(cause: Throwable? = null) : AppError("Failed to transcribe voice message", cause)
    class WorkoutGenerationFailed(cause: Throwable? = null) : AppError("Failed to generate workout plan", cause)
    class FeedbackAnalysisFailed(cause: Throwable? = null) : AppError("Failed to analyze feedback", cause)
    
    // Database Errors
    class DatabaseUnavailable(cause: Throwable? = null) : AppError("Database is currently unavailable", cause)
    class DatabaseOperationFailed(cause: Throwable? = null) : AppError("Database operation failed", cause)
    
    // Validation Errors
    class ValidationError(override val message: String) : AppError(message)
    class InvalidInput(override val message: String) : AppError(message)
    
    // Business Logic Errors
    class UserNotFound(val userId: Long) : AppError("User $userId not found")
    class WorkoutNotFound(val workoutId: String) : AppError("Workout $workoutId not found")
    object SubscriptionLimitExceeded : AppError("Free monthly limit exceeded")
    class InvalidStateTransition(val from: String, val to: String) : AppError("Invalid state transition from $from to $to")
    
    // Unexpected Errors
    class UnexpectedError(cause: Throwable? = null) : AppError("An unexpected error occurred", cause)
}

