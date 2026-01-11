package com.kettlebell.service

import com.kettlebell.model.ActualPerformance
import com.kettlebell.model.WorkoutContext
import com.kettlebell.model.WorkoutPlan

interface AIService {
    suspend fun generateWorkoutPlan(context: WorkoutContext): WorkoutPlan

    suspend fun transcribeVoice(audioFile: ByteArray): String

    suspend fun analyzeFeedback(
        feedback: String,
        originalPlan: WorkoutPlan,
    ): ActualPerformance
}
