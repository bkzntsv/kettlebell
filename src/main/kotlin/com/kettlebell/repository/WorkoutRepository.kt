package com.kettlebell.repository

import com.kettlebell.model.ActualPerformance
import com.kettlebell.model.Workout
import com.kettlebell.model.WorkoutStatus
import java.time.Instant

interface WorkoutRepository {
    suspend fun save(workout: Workout): Workout

    suspend fun findById(workoutId: String): Workout?

    suspend fun findByUserId(
        userId: Long,
        limit: Int,
    ): List<Workout>

    suspend fun findRecentByUserId(
        userId: Long,
        count: Int,
    ): List<Workout>

    suspend fun countCompletedWorkoutsAfter(
        userId: Long,
        date: Instant,
    ): Long

    suspend fun updateStatus(
        workoutId: String,
        status: WorkoutStatus,
    )

    suspend fun saveActualPerformance(
        workoutId: String,
        performance: ActualPerformance,
    )
}
