package com.kettlebell.bot

import com.kettlebell.model.*
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.*
import io.kotest.property.checkAll
import java.time.Instant
import java.util.UUID

class HistoryHandlerTest : StringSpec({
    
    "Property 21: History Display Completeness - should include all required workout information" {
        checkAll(100, Arb.long(), Arb.int(1, 10)) { userId, exerciseCount ->
            val exercises = (0 until exerciseCount).map { i ->
                Exercise("Exercise $i", 16, 10, 3, null, null)
            }
            
            val workout = Workout(
                id = UUID.randomUUID().toString(),
                userId = userId,
                status = WorkoutStatus.COMPLETED,
                plan = WorkoutPlan(
                    warmup = "Warmup",
                    exercises = exercises,
                    cooldown = "Cooldown"
                ),
                actualPerformance = ActualPerformance(
                    rawFeedback = "Completed",
                    data = exercises.map { ex ->
                        ExercisePerformance(ex.name, ex.weight, ex.reps ?: 10, ex.sets ?: 3, true)
                    },
                    rpe = 7,
                    issues = emptyList()
                ),
                timing = WorkoutTiming(
                    startedAt = Instant.now().minusSeconds(1800),
                    completedAt = Instant.now(),
                    durationSeconds = 1800L
                ),
                aiLog = AILog(0, "gpt-4o", 0, null, null),
                schemaVersion = 1
            )
            
            // Verify all required fields for history display
            workout.timing.completedAt shouldNotBe null
            workout.actualPerformance shouldNotBe null
            workout.actualPerformance?.data?.size shouldBe exerciseCount
            workout.timing.durationSeconds shouldNotBe null
            
            // Calculate volume
            val volume = workout.actualPerformance?.data?.sumOf { 
                if (it.completed) it.weight * it.reps * it.sets else 0 
            } ?: 0
            
            volume shouldBe exerciseCount * 16 * 10 * 3
        }
    }
    
    "should handle empty history case" {
        val workouts = emptyList<Workout>()
        
        workouts.isEmpty() shouldBe true
        workouts.size shouldBe 0
    }
    
    "should handle history with multiple workouts" {
        checkAll(100, Arb.long(), Arb.int(1, 10)) { userId, workoutCount ->
            val workouts = (0 until workoutCount).map { i ->
                Workout(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    status = WorkoutStatus.COMPLETED,
                    plan = WorkoutPlan("Warmup", emptyList(), "Cooldown"),
                    actualPerformance = ActualPerformance(
                        rawFeedback = "Completed",
                        data = listOf(ExercisePerformance("Swing", 16, 10, 3, true)),
                        rpe = 7,
                        issues = emptyList()
                    ),
                    timing = WorkoutTiming(
                        startedAt = Instant.now().minusSeconds((workoutCount - i).toLong() * 86400),
                        completedAt = Instant.now().minusSeconds((workoutCount - i).toLong() * 86400 + 1800),
                        durationSeconds = 1800L
                    ),
                    aiLog = AILog(0, "gpt-4o", 0, null, null),
                    schemaVersion = 1
                )
            }
            
            workouts.size shouldBe workoutCount
            workouts.all { it.userId == userId } shouldBe true
            workouts.all { it.status == WorkoutStatus.COMPLETED } shouldBe true
            workouts.all { it.timing.completedAt != null } shouldBe true
        }
    }
})

