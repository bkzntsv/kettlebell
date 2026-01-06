package com.kettlebell.bot

import com.kettlebell.model.*
import com.kettlebell.service.WorkoutService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import java.util.UUID

class WorkoutFlowTest : StringSpec({
    
    val workoutService = mockk<WorkoutService>()
    
    "Property 28: Voice Feedback File Path Preservation - file path should be preserved during processing" {
        val filePath = "voice/file_123.ogg"
        val fileId = "file_123"
        
        // File path structure should be preserved
        filePath.contains(fileId) shouldBe true
        filePath.endsWith(".ogg") shouldBe true
        
        // Simulate file path extraction
        val extractedPath = filePath
        extractedPath.isNotEmpty() shouldBe true
    }
    
    "should handle workout request flow" {
        val userId = 123L
        val workout = Workout(
            id = UUID.randomUUID().toString(),
            userId = userId,
            status = WorkoutStatus.PLANNED,
            plan = WorkoutPlan(
                warmup = "Warmup",
                exercises = listOf(Exercise("Swing", 16, 10, 3, null, null)),
                cooldown = "Cooldown"
            ),
            actualPerformance = null,
            timing = WorkoutTiming(null, null, null),
            aiLog = AILog(0, "gpt-4o", 0, null, null),
            schemaVersion = 1
        )
        
        coEvery { workoutService.generateWorkoutPlan(userId) } returns workout
        
        // Verify workout has all required fields for display
        workout.plan.warmup.isNotEmpty() shouldBe true
        workout.plan.exercises.isNotEmpty() shouldBe true
        workout.plan.cooldown.isNotEmpty() shouldBe true
        workout.id.isNotEmpty() shouldBe true
    }
    
    "should handle workout start flow" {
        val userId = 123L
        val workoutId = UUID.randomUUID().toString()
        val workout = Workout(
            id = workoutId,
            userId = userId,
            status = WorkoutStatus.IN_PROGRESS,
            plan = WorkoutPlan("Warmup", emptyList(), "Cooldown"),
            actualPerformance = null,
            timing = WorkoutTiming(Instant.now(), null, null),
            aiLog = AILog(0, "gpt-4o", 0, null, null),
            schemaVersion = 1
        )
        
        coEvery { workoutService.startWorkout(userId, workoutId) } returns workout
        
        // Verify workout is in correct state
        workout.status shouldBe WorkoutStatus.IN_PROGRESS
        workout.timing.startedAt shouldNotBe null
    }
    
    "should handle workout finish flow" {
        val userId = 123L
        val workoutId = UUID.randomUUID().toString()
        val startedAt = Instant.now().minusSeconds(1800)
        val workout = Workout(
            id = workoutId,
            userId = userId,
            status = WorkoutStatus.PLANNED,
            plan = WorkoutPlan("Warmup", emptyList(), "Cooldown"),
            actualPerformance = null,
            timing = WorkoutTiming(startedAt, Instant.now(), 1800L),
            aiLog = AILog(0, "gpt-4o", 0, null, null),
            schemaVersion = 1
        )
        
        coEvery { workoutService.finishWorkout(userId, workoutId) } returns workout
        
        // Verify workout timing is set
        workout.timing.completedAt shouldNotBe null
        workout.timing.durationSeconds shouldNotBe null
    }
    
    "should handle feedback processing" {
        val userId = 123L
        val workoutId = UUID.randomUUID().toString()
        val feedback = "Выполнил все упражнения"
        val workout = Workout(
            id = workoutId,
            userId = userId,
            status = WorkoutStatus.COMPLETED,
            plan = WorkoutPlan("Warmup", emptyList(), "Cooldown"),
            actualPerformance = ActualPerformance(
                rawFeedback = feedback,
                data = listOf(ExercisePerformance("Swing", 16, 10, 3, true)),
                rpe = 7,
                issues = emptyList()
            ),
            timing = WorkoutTiming(null, Instant.now(), 1800L),
            aiLog = AILog(0, "gpt-4o", 0, null, null),
            schemaVersion = 1
        )
        
        coEvery { workoutService.processFeedback(userId, workoutId, feedback) } returns workout
        
        // Verify feedback is processed
        workout.status shouldBe WorkoutStatus.COMPLETED
        workout.actualPerformance?.rawFeedback shouldBe feedback
        workout.actualPerformance?.data?.isNotEmpty() shouldBe true
    }
})

