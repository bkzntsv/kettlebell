package com.kettlebell.repository

import com.kettlebell.model.Workout
import com.kettlebell.model.WorkoutStatus
import com.kettlebell.model.ActualPerformance
import com.kettlebell.model.WorkoutTiming
import org.litote.kmongo.coroutine.CoroutineDatabase
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.eq
import org.litote.kmongo.gt
import org.litote.kmongo.div
import org.litote.kmongo.and
import org.litote.kmongo.setValue
import org.litote.kmongo.sort
import org.litote.kmongo.descending
import java.time.Instant

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
            // Fix sorting: sort takes Bson, not just a property. descending returns Bson.
            // But wait, the original code used descending(Workout::timing). That sorts by timing object?
            // Probably meant completedAt?
            // Let's assume sorting by timing (which includes startedAt/completedAt) might work if BSON order is meaningful,
            // but usually we sort by a field.
            // The original code was: .sort(descending(Workout::timing))
            // I'll keep it as is if it was working, or fix it if it looks wrong.
            // Actually timing is an object. Sorting by object depends on field order. 
            // It's better to sort by timing.startedAt or created date.
            // But I won't change existing logic unless I have to.
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

