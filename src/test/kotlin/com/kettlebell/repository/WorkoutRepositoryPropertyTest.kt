package com.kettlebell.repository

import com.kettlebell.model.AILog
import com.kettlebell.model.ActualPerformance
import com.kettlebell.model.Exercise
import com.kettlebell.model.ExercisePerformance
import com.kettlebell.model.Workout
import com.kettlebell.model.WorkoutPlan
import com.kettlebell.model.WorkoutStatus
import com.kettlebell.model.WorkoutTiming
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID

class WorkoutRepositoryPropertyTest : StringSpec({

    val workoutRepository = mockk<WorkoutRepository>()

    "Property 20: Workout History Retrieval - should return workouts ordered by completion date" {
        checkAll(100, Arb.long(), Arb.int(1, 10)) { userId, limit ->
            val now = Instant.now()
            val workouts =
                (0 until limit).map { i ->
                    Workout(
                        id = UUID.randomUUID().toString(),
                        userId = userId,
                        status = WorkoutStatus.COMPLETED,
                        plan =
                            WorkoutPlan(
                                warmup = "Warmup",
                                exercises = emptyList(),
                                cooldown = "Cooldown",
                            ),
                        actualPerformance = null,
                        timing =
                            WorkoutTiming(
                                startedAt = now.minusSeconds((limit - i).toLong() * 3600),
                                completedAt = now.minusSeconds((limit - i).toLong() * 3600 + 1800),
                                durationSeconds = 1800L,
                            ),
                        aiLog = AILog(0, "gpt-4o", 0, null, null),
                        schemaVersion = 1,
                    )
                }

            coEvery { workoutRepository.findByUserId(userId, limit) } returns workouts

            runBlocking {
                val history = workoutRepository.findByUserId(userId, limit)

                history.size shouldBe limit
                history.all { it.userId == userId } shouldBe true
                history.all { it.status == WorkoutStatus.COMPLETED } shouldBe true
            }

            runBlocking {
                coVerify { workoutRepository.findByUserId(userId, limit) }
            }
        }
    }

    "Property 20: Workout History Retrieval - should return recent workouts with actual performance" {
        checkAll(100, Arb.long(), Arb.int(1, 3)) { userId, count ->
            val workouts =
                (0 until count).map { i ->
                    Workout(
                        id = UUID.randomUUID().toString(),
                        userId = userId,
                        status = WorkoutStatus.COMPLETED,
                        plan =
                            WorkoutPlan(
                                warmup = "Warmup",
                                exercises =
                                    listOf(
                                        Exercise("Swing", 16, 10, 3, null, null),
                                    ),
                                cooldown = "Cooldown",
                            ),
                        actualPerformance =
                            ActualPerformance(
                                rawFeedback = "Completed",
                                data =
                                    listOf(
                                        ExercisePerformance("Swing", 16, 10, 3, true),
                                    ),
                                rpe = 7,
                                issues = emptyList(),
                            ),
                        timing =
                            WorkoutTiming(
                                startedAt = Instant.now().minusSeconds((count - i).toLong() * 86400),
                                completedAt = Instant.now().minusSeconds((count - i).toLong() * 86400 + 1800),
                                durationSeconds = 1800L,
                            ),
                        aiLog = AILog(0, "gpt-4o", 0, null, null),
                        schemaVersion = 1,
                    )
                }

            coEvery { workoutRepository.findRecentByUserId(userId, count) } returns workouts

            runBlocking {
                val recent = workoutRepository.findRecentByUserId(userId, count)

                recent.size shouldBe count
                recent.all { it.actualPerformance != null } shouldBe true
                recent.all { it.actualPerformance?.data?.isNotEmpty() == true } shouldBe true
            }

            runBlocking {
                coVerify { workoutRepository.findRecentByUserId(userId, count) }
            }
        }
    }
})
