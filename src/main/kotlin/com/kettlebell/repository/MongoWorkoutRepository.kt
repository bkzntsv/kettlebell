package com.kettlebell.repository

import com.kettlebell.model.ActualPerformance
import com.kettlebell.model.Workout
import com.kettlebell.model.WorkoutStatus
import com.kettlebell.model.WorkoutTiming
import com.mongodb.client.model.ReplaceOptions
import org.litote.kmongo.and
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.coroutine.CoroutineDatabase
import org.litote.kmongo.descending
import org.litote.kmongo.div
import org.litote.kmongo.eq
import org.litote.kmongo.gt
import org.litote.kmongo.setValue
import java.time.Instant

class MongoWorkoutRepository(
    database: CoroutineDatabase
) : WorkoutRepository {
    private val collection: CoroutineCollection<Workout> = 
        database.getCollection<Workout>("workouts")
    
    override suspend fun save(workout: Workout): Workout {
        collection.replaceOne(
            Workout::id eq workout.id,
            workout,
            ReplaceOptions().upsert(true)
        )
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

    override suspend fun countCompletedWorkoutsAfter(userId: Long, date: Instant): Long {
        return collection.countDocuments(
            and(
                Workout::userId eq userId,
                Workout::status eq WorkoutStatus.COMPLETED,
                Workout::timing / WorkoutTiming::completedAt gt date
            )
        )
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

