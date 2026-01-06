package com.kettlebell.service

import com.kettlebell.model.WorkoutContext
import com.kettlebell.model.WorkoutPlan
import com.kettlebell.model.ActualPerformance

interface AIService {
    suspend fun generateWorkoutPlan(context: WorkoutContext): WorkoutPlan
    suspend fun transcribeVoice(audioFile: ByteArray): String
    suspend fun analyzeFeedback(feedback: String, originalPlan: WorkoutPlan): ActualPerformance
}

