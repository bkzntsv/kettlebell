package com.kettlebell.service

import com.kettlebell.model.AILog
import com.kettlebell.model.ActualPerformance
import com.kettlebell.model.ExercisePerformance
import com.kettlebell.model.Workout
import com.kettlebell.model.WorkoutPlan
import com.kettlebell.model.WorkoutStatus
import com.kettlebell.model.WorkoutTiming
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.util.UUID

class VolumeCalculationTest : StringSpec({

    val workoutService = WorkoutServiceImpl(mockk(), mockk(), mockk(), mockk())

    "should calculate volume correctly for bilateral exercises" {
        // Swing: 16kg * 10 reps * 4 sets = 640
        val exercise = ExercisePerformance("Swing", 16, 10, 4, true)
        val workout = createWorkoutWithPerformance(listOf(exercise))

        workoutService.calculateTotalVolume(workout) shouldBe 640
    }

    "should calculate volume correctly for unilateral exercises" {
        val weight = 16
        val reps = 5
        val setsPerSide = 3
        val totalSets = setsPerSide * 2 // 6 sets total

        // Press: 16kg * 5 reps * 6 sets = 480
        val exercise = ExercisePerformance("Press", weight, reps, totalSets, true)
        val workout = createWorkoutWithPerformance(listOf(exercise))

        workoutService.calculateTotalVolume(workout) shouldBe 480
    }

    "should calculate volume correctly for timed exercises (input as seconds)" {
        // Carry: 16kg * 40 reps (seconds) * 2 sets = 1280
        val exercise = ExercisePerformance("Carry", 16, 40, 2, true)
        val workout = createWorkoutWithPerformance(listOf(exercise))

        workoutService.calculateTotalVolume(workout) shouldBe 1280
    }

    "should DOUBLE volume for Double Kettlebell exercises" {
        // "Double Swing": 16kg (per bell) * 2 bells * 10 reps * 3 sets = 960
        val exercise = ExercisePerformance("Double Swing", 16, 10, 3, true)
        val workout = createWorkoutWithPerformance(listOf(exercise))

        workoutService.calculateTotalVolume(workout) shouldBe 960
    }

    "should DOUBLE volume for Russian named Double exercises" {
        // "Двойной фронтальный присед": 16kg * 2 * 5 * 5 = 800
        val exercise = ExercisePerformance("Двойной фронтальный присед", 16, 5, 5, true)
        val workout = createWorkoutWithPerformance(listOf(exercise))

        workoutService.calculateTotalVolume(workout) shouldBe 800
    }

    "should calculate volume as ZERO for bodyweight exercises (weight = 0)" {
        // Pushups: 0kg * 20 reps * 3 sets = 0
        val exercise = ExercisePerformance("Pushups", 0, 20, 3, true)
        val workout = createWorkoutWithPerformance(listOf(exercise))

        workoutService.calculateTotalVolume(workout) shouldBe 0
    }

    "should sum up volume for mixed workout" {
        val exercisesFixed =
            listOf(
                // 16*12*4 = 768
                ExercisePerformance("Swing", 16, 12, 4, true),
                // (16*2)*8*4 = 1024
                ExercisePerformance("Double Squat", 16, 8, 4, true),
                // 16*5*6 = 480
                ExercisePerformance("Press", 16, 5, 6, true),
                // 16*8*6 = 768
                ExercisePerformance("Row", 16, 8, 6, true),
                // 16*40*2 = 1280
                ExercisePerformance("Carry", 16, 40, 2, true),
                // 16*10*3 = 480
                ExercisePerformance("EMOM Swings", 16, 10, 3, true),
            )
        // Total: 768+1024+480+768+1280+480 = 4800

        val workout = createWorkoutWithPerformance(exercisesFixed)
        workoutService.calculateTotalVolume(workout) shouldBe 4800
    }

    "should calculate volume correctly for EMOM with 2 movements (two rows)" {
        // EMOM 8 min: odd minutes 12 swings, even minutes 6 goblet squats
        // Each movement: reps per round, sets = minutes/2 = 4 rounds
        // Финишер EMOM — махи: 16*12*4 = 768
        // Финишер EMOM — приседы: 16*6*4 = 384
        val emomSwings = ExercisePerformance("Финишер EMOM — махи", 16, 12, 4, true)
        val emomSquats = ExercisePerformance("Финишер EMOM — приседы", 16, 6, 4, true)
        val workout = createWorkoutWithPerformance(listOf(emomSwings, emomSquats))

        workoutService.calculateTotalVolume(workout) shouldBe 768 + 384 // 1152
    }

    "should calculate volume for full workout with EMOM as two rows (realistic actual_data shape)" {
        // Данные как при разборе отзыва по плану: махи 12×5, гоблет 10×4, жим 5×4, тяга 8×4, ношение 45с×2, EMOM 8 мин (махи 12×4, приседы 6×4)
        val actualData =
            listOf(
                // 16*12*5 = 960
                ExercisePerformance("Двуручный мах", 16, 12, 5, true),
                // 16*10*4 = 640
                ExercisePerformance("Гоблет‑присед", 16, 10, 4, true),
                // 16*5*4 = 320
                ExercisePerformance("Жим вверх одной рукой", 16, 5, 4, true),
                // 16*8*4 = 512
                ExercisePerformance("Тяга в наклоне одной рукой", 16, 8, 4, true),
                // 16*45*2 = 1440 (сек × подходы)
                ExercisePerformance("Ношение в чемодане", 16, 45, 2, true),
                // 16*12*4 = 768
                ExercisePerformance("Финишер EMOM — махи", 16, 12, 4, true),
                // 16*6*4 = 384
                ExercisePerformance("Финишер EMOM — приседы", 16, 6, 4, true),
            )
        val workout = createWorkoutWithPerformance(actualData)
        // 5024
        val expectedVolume = 960 + 640 + 320 + 512 + 1440 + 768 + 384
        workoutService.calculateTotalVolume(workout) shouldBe expectedVolume
    }

    "should include volume from partial/failed exercises if data is present" {
        val exercises =
            listOf(
                // 480
                ExercisePerformance("Swing", 16, 10, 3, true),
                // 160
                ExercisePerformance("Snatch", 16, 5, 2, false),
            )
        val workout = createWorkoutWithPerformance(exercises)
        workoutService.calculateTotalVolume(workout) shouldBe 640
    }

    "should return 0 for workout without actual performance" {
        val workout =
            Workout(
                id = UUID.randomUUID().toString(),
                userId = 1L,
                status = WorkoutStatus.PLANNED,
                plan = WorkoutPlan("", emptyList(), ""),
                actualPerformance = null,
                timing = WorkoutTiming(null, null, null),
                aiLog = AILog(0, "", 0, null, null),
                schemaVersion = 1,
            )
        workoutService.calculateTotalVolume(workout) shouldBe 0
    }
})

fun createWorkoutWithPerformance(data: List<ExercisePerformance>): Workout {
    return Workout(
        id = UUID.randomUUID().toString(),
        userId = 1L,
        status = WorkoutStatus.COMPLETED,
        plan = WorkoutPlan("", emptyList(), ""),
        actualPerformance =
            ActualPerformance(
                rawFeedback = "",
                data = data,
                rpe = null,
                issues = emptyList(),
            ),
        timing = WorkoutTiming(null, null, null),
        aiLog = AILog(0, "", 0, null, null),
        schemaVersion = 1,
    )
}
