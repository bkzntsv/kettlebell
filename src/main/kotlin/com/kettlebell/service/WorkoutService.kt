package com.kettlebell.service

import com.kettlebell.model.Workout

interface WorkoutService {
    suspend fun generateWorkoutPlan(userId: Long): Workout

    suspend fun startWorkout(
        userId: Long,
        workoutId: String,
    ): Workout

    suspend fun finishWorkout(
        userId: Long,
        workoutId: String,
    ): Workout

    suspend fun processFeedback(
        userId: Long,
        workoutId: String,
        feedback: String,
    ): Workout

    suspend fun getWorkoutHistory(
        userId: Long,
        limit: Int = 10,
    ): List<Workout>

    suspend fun calculateTotalVolume(workout: Workout): Int
}
