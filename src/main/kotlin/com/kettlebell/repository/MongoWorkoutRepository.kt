package com.kettlebell.repository

import com.kettlebell.model.Workout
import com.kettlebell.model.WorkoutStatus
import com.kettlebell.model.ActualPerformance
import org.litote.kmongo.coroutine.CoroutineDatabase
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.eq
import org.litote.kmongo.setValue
import org.litote.kmongo.sort
import org.litote.kmongo.descending

class MongoWorkoutRepository(
    private val database: CoroutineDatabase
) : WorkoutRepository {
    private val collection: CoroutineCollection<Workout> = 
        database.getCollection<Workout>("workouts")
    
    override suspend fun save(workout: Workout): Workout {
        collection.save(workout)
        return workout
    }
    
    override suspend fun findById(workoutId: String): Workout? {
        return collection.findOne(Workout::id eq workoutId)
    }
    
    override suspend fun findByUserId(userId: Long, limit: Int): List<Workout> {
        return collection.find(Workout::userId eq userId)
            .sort(descending(Workout::timing))
            .limit(limit)
            .toList()
    }
    
    override suspend fun findRecentByUserId(userId: Long, count: Int): List<Workout> {
        return collection.find(Workout::userId eq userId)
            .sort(descending(Workout::timing))
            .limit(count)
            .toList()
    }
    
    override suspend fun updateStatus(workoutId: String, status: WorkoutStatus) {
        collection.updateOne(
            Workout::id eq workoutId,
            setValue(Workout::status, status)
        )
    }
    
    override suspend fun saveActualPerformance(workoutId: String, performance: ActualPerformance) {
        collection.updateOne(
            Workout::id eq workoutId,
            setValue(Workout::actualPerformance, performance)
        )
    }
}

