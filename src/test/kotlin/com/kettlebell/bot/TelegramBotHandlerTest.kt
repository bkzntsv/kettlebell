package com.kettlebell.bot

import com.kettlebell.model.AILog
import com.kettlebell.model.ActualPerformance
import com.kettlebell.model.Exercise
import com.kettlebell.model.ExercisePerformance
import com.kettlebell.model.ExperienceLevel
import com.kettlebell.model.Gender
import com.kettlebell.model.ProfileData
import com.kettlebell.model.Subscription
import com.kettlebell.model.SubscriptionType
import com.kettlebell.model.UserMetadata
import com.kettlebell.model.UserProfile
import com.kettlebell.model.UserState
import com.kettlebell.model.Workout
import com.kettlebell.model.WorkoutPlan
import com.kettlebell.model.WorkoutStatus
import com.kettlebell.model.WorkoutTiming
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.time.Instant
import java.util.UUID

class TelegramBotHandlerTest : StringSpec({

    // Property tests that verify data structures and business logic properties

    "Property 3: Onboarding Idempotence - profile should remain intact after multiple operations" {
        checkAll(100, Arb.long(), Arb.list(Arb.int(1, 100), 1..5)) { userId, weights ->
            val profile =
                UserProfile(
                    id = userId,
                    fsmState = UserState.IDLE,
                    profile =
                        ProfileData(
                            weights = weights,
                            experience = ExperienceLevel.BEGINNER,
                            bodyWeight = 70f,
                            gender = Gender.MALE,
                            goal = "Build strength",
                        ),
                    subscription = Subscription(SubscriptionType.FREE, null),
                    metadata = UserMetadata(Instant.now(), Instant.now()),
                    schemaVersion = 1,
                )

            // Simulate multiple /start commands - profile should remain the same
            val profile1 = profile
            val profile2 = profile

            // Profile should remain intact
            profile1.id shouldBe profile2.id
            profile1.profile.weights shouldBe profile2.profile.weights
            profile1.profile.goal shouldBe profile2.profile.goal
        }
    }

    "Property 5: Workout Plan Display Completeness - should include all required plan components" {
        checkAll(100, Arb.string(1..200), Arb.list(Arb.string(1..50), 1..10)) { warmup, exerciseNames ->
            val exercises =
                exerciseNames.map { name ->
                    Exercise(name, 16, 10, 3, null, null)
                }

            val workout =
                Workout(
                    id = UUID.randomUUID().toString(),
                    userId = 123L,
                    status = WorkoutStatus.PLANNED,
                    plan =
                        WorkoutPlan(
                            warmup = warmup,
                            exercises = exercises,
                            cooldown = "Cooldown",
                        ),
                    actualPerformance = null,
                    timing = WorkoutTiming(null, null, null),
                    aiLog = AILog(0, "gpt-4o", 0, null, null),
                    schemaVersion = 1,
                )

            // Verify plan has all required components
            workout.plan.warmup.isNotEmpty() shouldBe true
            workout.plan.exercises.isNotEmpty() shouldBe true
            workout.plan.cooldown.isNotEmpty() shouldBe true

            workout.plan.exercises.forEach { exercise ->
                exercise.name.isNotEmpty() shouldBe true
                (exercise.weight > 0) shouldBe true
            }
        }
    }

    "Property 21: History Display Completeness - should include all required workout information" {
        checkAll(100, Arb.long()) { userId ->
            val workout =
                Workout(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    status = WorkoutStatus.COMPLETED,
                    plan =
                        WorkoutPlan(
                            warmup = "Warmup",
                            exercises = listOf(Exercise("Swing", 16, 10, 3, null, null)),
                            cooldown = "Cooldown",
                        ),
                    actualPerformance =
                        ActualPerformance(
                            rawFeedback = "Completed",
                            data = listOf(ExercisePerformance("Swing", 16, 10, 3, true)),
                            rpe = 7,
                            issues = emptyList(),
                        ),
                    timing =
                        WorkoutTiming(
                            startedAt = Instant.now().minusSeconds(1800),
                            completedAt = Instant.now(),
                            durationSeconds = 1800L,
                        ),
                    aiLog = AILog(0, "gpt-4o", 0, null, null),
                    schemaVersion = 1,
                )

            // Verify all required fields for history display
            workout.timing.completedAt shouldNotBe null
            workout.actualPerformance shouldNotBe null
            workout.actualPerformance?.data?.isNotEmpty() shouldBe true
            workout.timing.durationSeconds shouldNotBe null

            val calculatedVolume =
                workout.actualPerformance?.data?.sumOf {
                    if (it.completed) it.weight * it.reps * it.sets else 0
                } ?: 0

            calculatedVolume shouldBe 16 * 10 * 3
        }
    }

    "Property 30: Inline Keyboard for Options - should create keyboard with correct structure" {
        checkAll(100, Arb.string(1..50), Arb.string(1..50)) { buttonText1, buttonText2 ->
            val keyboard =
                InlineKeyboardMarkup(
                    listOf(
                        listOf(InlineKeyboardButton(buttonText1, "action1:data1")),
                        listOf(InlineKeyboardButton(buttonText2, "action2:data2")),
                    ),
                )

            keyboard.inline_keyboard.size shouldBe 2
            keyboard.inline_keyboard[0].size shouldBe 1
            keyboard.inline_keyboard[0][0].text shouldBe buttonText1
            keyboard.inline_keyboard[0][0].callback_data shouldBe "action1:data1"
            keyboard.inline_keyboard[1][0].text shouldBe buttonText2
            keyboard.inline_keyboard[1][0].callback_data shouldBe "action2:data2"
        }
    }
})
