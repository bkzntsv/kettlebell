package com.kettlebell.service

import com.aallam.openai.client.OpenAI
import com.kettlebell.config.AppConfig
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
import com.kettlebell.model.WorkoutContext
import com.kettlebell.model.WorkoutPlan
import com.kettlebell.model.WorkoutStatus
import com.kettlebell.model.WorkoutTiming
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

class AIServiceWorkoutPromptTest : StringSpec({

    val openAI = mockk<OpenAI>()
    val config = mockk<AppConfig>()
    val aiService = AIServiceImpl(openAI, config)
    val json = Json { ignoreUnknownKeys = true }

    fun createProfile(
        weights: List<Int> = listOf(16, 24),
        experience: ExperienceLevel = ExperienceLevel.BEGINNER,
        bodyWeight: Float = 70f,
        gender: Gender = Gender.MALE,
        goal: String = "Сила",
    ): UserProfile {
        return UserProfile(
            id = 1L,
            fsmState = UserState.IDLE,
            profile = ProfileData(weights, experience, bodyWeight, gender, goal),
            subscription = Subscription(SubscriptionType.FREE, null),
            metadata = UserMetadata(Instant.now(), Instant.now()),
        )
    }

    fun createWorkout(
        plan: WorkoutPlan,
        actualPerformance: ActualPerformance? = null,
        completedAt: Instant = Instant.now(),
    ): Workout {
        return Workout(
            userId = 1L,
            status = if (actualPerformance != null) WorkoutStatus.COMPLETED else WorkoutStatus.PLANNED,
            plan = plan,
            actualPerformance = actualPerformance,
            timing = WorkoutTiming(startedAt = completedAt.minusSeconds(3600), completedAt = completedAt, durationSeconds = 3600L),
            aiLog = AILog(100, "gpt-5-mini", 1000, null, "stop"),
        )
    }

    fun createWorkoutPlan(
        exercises: List<Exercise> =
            listOf(
                Exercise("Махи", 16, 10, 3, null, null),
            ),
    ): WorkoutPlan {
        return WorkoutPlan(
            warmup = "Разминка",
            exercises = exercises,
            cooldown = "Заминка",
        )
    }

    fun createActualPerformance(
        exercises: List<ExercisePerformance>,
        rpe: Int? = 7,
        recoveryStatus: String? = null,
        technicalNotes: String? = null,
        issues: List<String> = emptyList(),
    ): ActualPerformance {
        return ActualPerformance(
            rawFeedback = "Отзыв",
            data = exercises,
            rpe = rpe,
            recoveryStatus = recoveryStatus,
            technicalNotes = technicalNotes,
            issues = issues,
        )
    }

    // 1.1.1 История включает все поля
    "1.1.1 История включает все поля: date, planned_exercises, exercises, " +
        "total_volume_kg, rpe, recovery_status, red_flags, technical_notes" {
            val profile = createProfile()
            val plan =
                createWorkoutPlan(
                    listOf(
                        Exercise("Махи", 16, 10, 3, null, null),
                    ),
                )
            val completedAt = Instant.parse("2024-01-15T10:00:00Z")
            val actual =
                createActualPerformance(
                    listOf(
                        ExercisePerformance("Махи", 16, 10, 3, true, "completed"),
                    ),
                    rpe = 7,
                    recoveryStatus = "good",
                    technicalNotes = "Хорошая техника",
                    issues = listOf("Нет проблем"),
                )
            val workout = createWorkout(plan, actual, completedAt)
            val context = WorkoutContext(profile, listOf(workout), listOf(16, 24), 1, false)

            val prompt = aiService.buildWorkoutPrompt(context)
            val jsonElement = json.parseToJsonElement(prompt)
            val workoutJson = jsonElement.jsonObject["context"]?.jsonObject?.get("history")?.jsonArray?.get(0)?.jsonObject

            workoutJson shouldNotBe null

            workoutJson!!["date"]?.jsonPrimitive?.content shouldNotBe null
            workoutJson["planned_exercises"]?.jsonPrimitive?.content shouldNotBe null
            workoutJson["exercises"]?.jsonArray shouldNotBe null
            workoutJson["total_volume_kg"]?.jsonPrimitive?.content?.toIntOrNull() shouldBe 480
            workoutJson["rpe"]?.jsonPrimitive?.content?.toIntOrNull() shouldBe 7
            workoutJson["recovery_status"]?.jsonPrimitive?.content shouldBe "good"
            workoutJson["red_flags"]?.jsonArray shouldNotBe null
            workoutJson["technical_notes"]?.jsonPrimitive?.content shouldBe "Хорошая техника"

            val redFlags = workoutJson["red_flags"]?.jsonArray
            redFlags shouldNotBe null
            redFlags!!.size shouldBe 1
            redFlags[0].jsonPrimitive.content shouldBe "Нет проблем"
        }

    // 1.1.2 Общий объем рассчитывается корректно
    "1.1.2 Общий объем рассчитывается корректно (сумма weight × reps × sets)" {
        val profile = createProfile()
        val plan =
            createWorkoutPlan(
                listOf(
                    Exercise("Махи", 16, 10, 3, null, null),
                    Exercise("Турецкий подъем", 16, 5, 2, null, null),
                ),
            )
        val actual =
            createActualPerformance(
                listOf(
                    ExercisePerformance("Махи", 16, 10, 3, true, "completed"),
                    ExercisePerformance("Турецкий подъем", 16, 5, 2, true, "completed"),
                ),
            )
        val workout = createWorkout(plan, actual)
        val context = WorkoutContext(profile, listOf(workout), listOf(16, 24), 1, false)

        val prompt = aiService.buildWorkoutPrompt(context)

        val expectedVolume = 640
        prompt shouldContain "\"total_volume_kg\": $expectedVolume"

        val jsonElement = json.parseToJsonElement(prompt)
        val history = jsonElement.jsonObject["context"]?.jsonObject?.get("history")?.jsonArray
        history shouldNotBe null
        history!!.size shouldBe 1
        val workoutJson = history[0].jsonObject
        val volume = workoutJson["total_volume_kg"]?.jsonPrimitive?.content?.toIntOrNull()
        volume shouldBe expectedVolume
    }

    // 1.1.3 Используются только последние 3 тренировки с actualPerformance
    "1.1.3 Используются только последние 3 тренировки с actualPerformance" {
        val profile = createProfile()
        val now = Instant.now()
        val workouts =
            (1..5).map { i ->
                val plan =
                    createWorkoutPlan(
                        listOf(Exercise("Махи $i", 16, 10, 3, null, null)),
                    )
                val actual =
                    if (i <= 3) {
                        createActualPerformance(listOf(ExercisePerformance("Махи $i", 16, 10, 3, true, "completed")))
                    } else {
                        null
                    }
                createWorkout(plan, actual, now.minusSeconds(i * 86400L))
            }
        val context = WorkoutContext(profile, workouts, listOf(16, 24), 1, false)

        val prompt = aiService.buildWorkoutPrompt(context)

        val jsonElement = json.parseToJsonElement(prompt)
        val history = jsonElement.jsonObject["context"]?.jsonObject?.get("history")?.jsonArray
        history shouldNotBe null
        history!!.size shouldBe 3

        val dates = history.map { it.jsonObject["date"]?.jsonPrimitive?.content }
        dates.size shouldBe 3
        dates.all { it != null } shouldBe true
    }

    // 1.1.4 Тренировки без actualPerformance исключаются из истории
    "1.1.4 Тренировки без actualPerformance исключаются из истории" {
        val profile = createProfile()
        val workouts =
            listOf(
                // без actualPerformance
                createWorkout(createWorkoutPlan(), null),
                createWorkout(
                    createWorkoutPlan(),
                    createActualPerformance(listOf(ExercisePerformance("Махи", 16, 10, 3, true, "completed"))),
                ),
                // без actualPerformance
                createWorkout(createWorkoutPlan(), null),
            )
        val context = WorkoutContext(profile, workouts, listOf(16, 24), 1, false)

        val prompt = aiService.buildWorkoutPrompt(context)

        val dateMatches = "\"date\":".toRegex().findAll(prompt).count()
        dateMatches shouldBe 1
    }

    // 1.2.1 Для каждого упражнения передаются planned параметры
    "1.2.1 Для каждого упражнения передаются planned параметры (weight, reps, sets, timeWork, timeRest)" {
        val profile = createProfile()
        val plan =
            createWorkoutPlan(
                listOf(
                    Exercise("Махи", 16, 10, 3, null, null),
                    Exercise("Планка", 0, null, null, 30, 60),
                ),
            )
        val actual =
            createActualPerformance(
                listOf(
                    ExercisePerformance("Махи", 16, 10, 3, true, "completed"),
                    ExercisePerformance("Планка", 0, 0, 0, true, "completed"),
                ),
            )
        val workout = createWorkout(plan, actual)
        val context = WorkoutContext(profile, listOf(workout), listOf(16, 24), 1, false)

        val prompt = aiService.buildWorkoutPrompt(context)
        val jsonElement = json.parseToJsonElement(prompt)
        val exercises =
            jsonElement.jsonObject["context"]?.jsonObject?.get("history")?.jsonArray?.get(0)
                ?.jsonObject?.get("exercises")?.jsonArray

        exercises shouldNotBe null
        exercises!!.size shouldBe 2

        val firstExercise = exercises[0].jsonObject
        firstExercise["name"]?.jsonPrimitive?.content shouldBe "Махи"
        firstExercise["planned_weight_kg"]?.jsonPrimitive?.content?.toIntOrNull() shouldBe 16
        firstExercise["planned_reps"]?.jsonPrimitive?.content?.toIntOrNull() shouldBe 10
        firstExercise["planned_sets"]?.jsonPrimitive?.content?.toIntOrNull() shouldBe 3

        val secondExercise = exercises[1].jsonObject
        secondExercise["name"]?.jsonPrimitive?.content shouldBe "Планка"
        secondExercise["planned_weight_kg"]?.jsonPrimitive?.content?.toIntOrNull() shouldBe 0
        secondExercise["planned_time_work_sec"]?.jsonPrimitive?.content?.toIntOrNull() shouldBe 30
        secondExercise["planned_time_rest_sec"]?.jsonPrimitive?.content?.toIntOrNull() shouldBe 60
    }

    // 1.2.2 Для каждого упражнения передаются actual параметры
    "1.2.2 Для каждого упражнения передаются actual параметры (weight, reps, sets)" {
        val profile = createProfile()
        val plan = createWorkoutPlan()
        val actual =
            createActualPerformance(
                listOf(
                    ExercisePerformance("Махи", 16, 10, 3, true, "completed"),
                ),
            )
        val workout = createWorkout(plan, actual)
        val context = WorkoutContext(profile, listOf(workout), listOf(16, 24), 1, false)

        val prompt = aiService.buildWorkoutPrompt(context)
        val jsonElement = json.parseToJsonElement(prompt)
        val exercise =
            jsonElement.jsonObject["context"]?.jsonObject?.get("history")?.jsonArray?.get(0)
                ?.jsonObject?.get("exercises")?.jsonArray?.get(0)?.jsonObject

        exercise shouldNotBe null
        exercise!!["actual_weight_kg"]?.jsonPrimitive?.content?.toIntOrNull() shouldBe 16
        exercise["actual_reps"]?.jsonPrimitive?.content?.toIntOrNull() shouldBe 10
        exercise["actual_sets"]?.jsonPrimitive?.content?.toIntOrNull() shouldBe 3
    }

    // 1.2.3 Статус выполнения корректно определяется
    "1.2.3 Статус выполнения корректно определяется (completed, partial, failed)" {
        val profile = createProfile()
        val plan =
            createWorkoutPlan(
                listOf(
                    Exercise("Махи", 16, 10, 3, null, null),
                ),
            )

        val actualCompleted =
            createActualPerformance(
                listOf(ExercisePerformance("Махи", 16, 10, 3, true, "completed")),
            )
        val workoutCompleted = createWorkout(plan, actualCompleted)
        val contextCompleted = WorkoutContext(profile, listOf(workoutCompleted), listOf(16, 24), 1, false)
        val promptCompleted = aiService.buildWorkoutPrompt(contextCompleted)
        val jsonCompleted = json.parseToJsonElement(promptCompleted)
        val exerciseCompleted =
            jsonCompleted.jsonObject["context"]?.jsonObject?.get("history")?.jsonArray?.get(0)
                ?.jsonObject?.get("exercises")?.jsonArray?.get(0)?.jsonObject
        exerciseCompleted?.get("status")?.jsonPrimitive?.content shouldBe "completed"

        val actualPartial =
            createActualPerformance(
                listOf(ExercisePerformance("Махи", 16, 8, 3, true, "partial")),
            )
        val workoutPartial = createWorkout(plan, actualPartial)
        val contextPartial = WorkoutContext(profile, listOf(workoutPartial), listOf(16, 24), 1, false)
        val promptPartial = aiService.buildWorkoutPrompt(contextPartial)
        val jsonPartial = json.parseToJsonElement(promptPartial)
        val exercisePartial =
            jsonPartial.jsonObject["context"]?.jsonObject?.get("history")?.jsonArray?.get(0)
                ?.jsonObject?.get("exercises")?.jsonArray?.get(0)?.jsonObject
        exercisePartial?.get("status")?.jsonPrimitive?.content shouldBe "partial"

        val actualFailed =
            createActualPerformance(
                listOf(ExercisePerformance("Махи", 16, 5, 2, false, "failed")),
            )
        val workoutFailed = createWorkout(plan, actualFailed)
        val contextFailed = WorkoutContext(profile, listOf(workoutFailed), listOf(16, 24), 1, false)
        val promptFailed = aiService.buildWorkoutPrompt(contextFailed)
        val jsonFailed = json.parseToJsonElement(promptFailed)
        val exerciseFailed =
            jsonFailed.jsonObject["context"]?.jsonObject?.get("history")?.jsonArray?.get(0)
                ?.jsonObject?.get("exercises")?.jsonArray?.get(0)?.jsonObject
        exerciseFailed?.get("status")?.jsonPrimitive?.content shouldBe "failed"

        val actualCompletedNullStatus =
            createActualPerformance(
                listOf(ExercisePerformance("Махи", 16, 10, 3, true, null)),
            )
        val workoutCompletedNullStatus = createWorkout(plan, actualCompletedNullStatus)
        val contextCompletedNullStatus = WorkoutContext(profile, listOf(workoutCompletedNullStatus), listOf(16, 24), 1, false)
        val promptCompletedNullStatus = aiService.buildWorkoutPrompt(contextCompletedNullStatus)
        val jsonCompletedNullStatus = json.parseToJsonElement(promptCompletedNullStatus)
        val exerciseCompletedNullStatus =
            jsonCompletedNullStatus.jsonObject["context"]?.jsonObject?.get("history")?.jsonArray?.get(0)
                ?.jsonObject?.get("exercises")?.jsonArray?.get(0)?.jsonObject
        exerciseCompletedNullStatus?.get("status")?.jsonPrimitive?.content shouldBe "completed"

        val actualFailedNullStatus =
            createActualPerformance(
                listOf(ExercisePerformance("Махи", 16, 5, 2, false, null)),
            )
        val workoutFailedNullStatus = createWorkout(plan, actualFailedNullStatus)
        val contextFailedNullStatus = WorkoutContext(profile, listOf(workoutFailedNullStatus), listOf(16, 24), 1, false)
        val promptFailedNullStatus = aiService.buildWorkoutPrompt(contextFailedNullStatus)
        val jsonFailedNullStatus = json.parseToJsonElement(promptFailedNullStatus)
        val exerciseFailedNullStatus =
            jsonFailedNullStatus.jsonObject["context"]?.jsonObject?.get("history")?.jsonArray?.get(0)
                ?.jsonObject?.get("exercises")?.jsonArray?.get(0)?.jsonObject
        exerciseFailedNullStatus?.get("status")?.jsonPrimitive?.content shouldBe "failed"
    }

    // 1.2.4 Если упражнение отсутствует в плане, planned параметры = 0 или null
    "1.2.4 Если упражнение отсутствует в плане, planned параметры = 0 или null" {
        val profile = createProfile()
        val plan =
            createWorkoutPlan(
                listOf(
                    Exercise("Махи", 16, 10, 3, null, null),
                ),
            )
        val actual =
            createActualPerformance(
                listOf(
                    ExercisePerformance("Махи", 16, 10, 3, true, "completed"),
                    ExercisePerformance("Новое упражнение", 24, 5, 2, true, "completed"),
                ),
            )
        val workout = createWorkout(plan, actual)
        val context = WorkoutContext(profile, listOf(workout), listOf(16, 24), 1, false)

        val prompt = aiService.buildWorkoutPrompt(context)
        val jsonElement = json.parseToJsonElement(prompt)
        val exercises =
            jsonElement.jsonObject["context"]?.jsonObject?.get("history")?.jsonArray?.get(0)
                ?.jsonObject?.get("exercises")?.jsonArray

        exercises shouldNotBe null
        exercises!!.size shouldBe 2

        val firstExercise = exercises[0].jsonObject
        firstExercise["name"]?.jsonPrimitive?.content shouldBe "Махи"
        firstExercise["planned_weight_kg"]?.jsonPrimitive?.content?.toIntOrNull() shouldBe 16
        firstExercise["planned_reps"]?.jsonPrimitive?.content?.toIntOrNull() shouldBe 10

        val secondExercise = exercises[1].jsonObject
        secondExercise["name"]?.jsonPrimitive?.content shouldBe "Новое упражнение"
        secondExercise["planned_weight_kg"]?.jsonPrimitive?.content?.toIntOrNull() shouldBe 0
        val plannedReps = secondExercise["planned_reps"]?.jsonPrimitive?.content
        (plannedReps == null || plannedReps == "null") shouldBe true
    }

    // 1.2.5 Если упражнение в actual, но не в planned, planned параметры = 0 или null
    "1.2.5 Если упражнение в actual, но не в planned, planned параметры = 0 или null" {
        val profile = createProfile()
        val plan = createWorkoutPlan(emptyList()) // Пустой план
        val actual =
            createActualPerformance(
                listOf(
                    ExercisePerformance("Махи", 16, 10, 3, true, "completed"),
                ),
            )
        val workout = createWorkout(plan, actual)
        val context = WorkoutContext(profile, listOf(workout), listOf(16, 24), 1, false)

        val prompt = aiService.buildWorkoutPrompt(context)

        prompt shouldContain "\"planned_weight_kg\": 0"
        prompt shouldContain "\"planned_reps\": null"
        prompt shouldContain "\"planned_sets\": null"
    }

    // 1.3.1 Recovery status включается в историю, если присутствует
    "1.3.1 Recovery status включается в историю, если присутствует" {
        val profile = createProfile()
        val plan = createWorkoutPlan()
        val actual =
            createActualPerformance(
                listOf(ExercisePerformance("Махи", 16, 10, 3, true, "completed")),
                recoveryStatus = "good",
            )
        val workout = createWorkout(plan, actual)
        val context = WorkoutContext(profile, listOf(workout), listOf(16, 24), 1, false)

        val prompt = aiService.buildWorkoutPrompt(context)

        prompt shouldContain "\"recovery_status\": \"good\""
    }

    // 1.3.2 Recovery status = null, если отсутствует
    "1.3.2 Recovery status = null, если отсутствует" {
        val profile = createProfile()
        val plan = createWorkoutPlan()
        val actual =
            createActualPerformance(
                listOf(ExercisePerformance("Махи", 16, 10, 3, true, "completed")),
                recoveryStatus = null,
            )
        val workout = createWorkout(plan, actual)
        val context = WorkoutContext(profile, listOf(workout), listOf(16, 24), 1, false)

        val prompt = aiService.buildWorkoutPrompt(context)

        prompt shouldContain "\"recovery_status\": null"
    }

    // 1.3.3 Technical notes включаются в историю, если присутствуют
    "1.3.3 Technical notes включаются в историю, если присутствуют" {
        val profile = createProfile()
        val plan = createWorkoutPlan()
        val actual =
            createActualPerformance(
                listOf(ExercisePerformance("Махи", 16, 10, 3, true, "completed")),
                technicalNotes = "Хорошая техника",
            )
        val workout = createWorkout(plan, actual)
        val context = WorkoutContext(profile, listOf(workout), listOf(16, 24), 1, false)

        val prompt = aiService.buildWorkoutPrompt(context)

        prompt shouldContain "\"technical_notes\": \"Хорошая техника\""
    }

    // 1.3.4 Technical notes = null, если отсутствуют
    "1.3.4 Technical notes = null, если отсутствуют" {
        val profile = createProfile()
        val plan = createWorkoutPlan()
        val actual =
            createActualPerformance(
                listOf(ExercisePerformance("Махи", 16, 10, 3, true, "completed")),
                technicalNotes = null,
            )
        val workout = createWorkout(plan, actual)
        val context = WorkoutContext(profile, listOf(workout), listOf(16, 24), 1, false)

        val prompt = aiService.buildWorkoutPrompt(context)

        prompt shouldContain "\"technical_notes\": null"
    }

    // 1.4.1 Кавычки в названиях упражнений экранируются
    "1.4.1 Кавычки в названиях упражнений экранируются" {
        val profile = createProfile()
        val plan =
            createWorkoutPlan(
                listOf(Exercise("Махи \"сильные\"", 16, 10, 3, null, null)),
            )
        val actual =
            createActualPerformance(
                listOf(ExercisePerformance("Махи \"сильные\"", 16, 10, 3, true, "completed")),
            )
        val workout = createWorkout(plan, actual)
        val context = WorkoutContext(profile, listOf(workout), listOf(16, 24), 1, false)

        val prompt = aiService.buildWorkoutPrompt(context)

        prompt shouldContain "Махи \\\"сильные\\\""
        prompt shouldNotContain "Махи \"сильные\""
    }

    // 1.4.2 Кавычки в technical_notes экранируются
    "1.4.2 Кавычки в technical_notes экранируются" {
        val profile = createProfile()
        val plan = createWorkoutPlan()
        val actual =
            createActualPerformance(
                listOf(ExercisePerformance("Махи", 16, 10, 3, true, "completed")),
                technicalNotes = "Техника \"отличная\"",
            )
        val workout = createWorkout(plan, actual)
        val context = WorkoutContext(profile, listOf(workout), listOf(16, 24), 1, false)

        val prompt = aiService.buildWorkoutPrompt(context)

        prompt shouldContain "Техника \\\"отличная\\\""
        prompt shouldNotContain "Техника \"отличная\""
    }

    // 1.4.3 Кавычки в recovery_status экранируются
    "1.4.3 Кавычки в recovery_status экранируются" {
        val profile = createProfile()
        val plan = createWorkoutPlan()
        val actual =
            createActualPerformance(
                listOf(ExercisePerformance("Махи", 16, 10, 3, true, "completed")),
                recoveryStatus = "Статус \"хороший\"",
            )
        val workout = createWorkout(plan, actual)
        val context = WorkoutContext(profile, listOf(workout), listOf(16, 24), 1, false)

        val prompt = aiService.buildWorkoutPrompt(context)

        prompt shouldContain "Статус \\\"хороший\\\""
        prompt shouldNotContain "Статус \"хороший\""
    }

    // 1.4.4 Кавычки в red_flags экранируются
    "1.4.4 Кавычки в red_flags экранируются" {
        val profile = createProfile()
        val plan = createWorkoutPlan()
        val actual =
            createActualPerformance(
                listOf(ExercisePerformance("Махи", 16, 10, 3, true, "completed")),
                issues = listOf("Боль в \"спине\""),
            )
        val workout = createWorkout(plan, actual)
        val context = WorkoutContext(profile, listOf(workout), listOf(16, 24), 1, false)

        val prompt = aiService.buildWorkoutPrompt(context)

        prompt shouldContain "Боль в \\\"спине\\\""
        prompt shouldNotContain "Боль в \"спине\""
    }

    // 1.5.1 История пустая (новый пользователь) — промпт формируется без history
    "1.5.1 История пустая (новый пользователь) — промпт формируется без history" {
        val profile = createProfile()
        val context = WorkoutContext(profile, emptyList(), listOf(16, 24), 1, false)

        val prompt = aiService.buildWorkoutPrompt(context)

        prompt shouldContain "\"history\": ["
        val historySection = prompt.substringAfter("\"history\": [").substringBefore("]")
        historySection.trim() shouldBe ""
    }

    // 1.5.2 Только 1 тренировка с actualPerformance — используется 1
    "1.5.2 Только 1 тренировка с actualPerformance — используется 1" {
        val profile = createProfile()
        val plan = createWorkoutPlan()
        val actual =
            createActualPerformance(
                listOf(ExercisePerformance("Махи", 16, 10, 3, true, "completed")),
            )
        val workout = createWorkout(plan, actual)
        val context = WorkoutContext(profile, listOf(workout), listOf(16, 24), 1, false)

        val prompt = aiService.buildWorkoutPrompt(context)

        val dateMatches = "\"date\":".toRegex().findAll(prompt).count()
        dateMatches shouldBe 1
    }

    // 1.5.3 Только 2 тренировки с actualPerformance — используются обе
    "1.5.3 Только 2 тренировки с actualPerformance — используются обе" {
        val profile = createProfile()
        val plan = createWorkoutPlan()
        val workouts =
            listOf(
                createWorkout(
                    plan,
                    createActualPerformance(listOf(ExercisePerformance("Махи", 16, 10, 3, true, "completed"))),
                    Instant.now().minusSeconds(86400),
                ),
                createWorkout(
                    plan,
                    createActualPerformance(listOf(ExercisePerformance("Махи", 16, 10, 3, true, "completed"))),
                    Instant.now(),
                ),
            )
        val context = WorkoutContext(profile, workouts, listOf(16, 24), 1, false)

        val prompt = aiService.buildWorkoutPrompt(context)

        val dateMatches = "\"date\":".toRegex().findAll(prompt).count()
        dateMatches shouldBe 2
    }

    // 1.5.4 5 тренировок, но только 2 с actualPerformance — используются 2
    "1.5.4 5 тренировок, но только 2 с actualPerformance — используются 2" {
        val profile = createProfile()
        val plan = createWorkoutPlan()
        val workouts =
            listOf(
                // без actualPerformance
                createWorkout(plan, null),
                createWorkout(
                    plan,
                    createActualPerformance(listOf(ExercisePerformance("Махи", 16, 10, 3, true, "completed"))),
                    Instant.now().minusSeconds(172800),
                ),
                // без actualPerformance
                createWorkout(plan, null),
                createWorkout(
                    plan,
                    createActualPerformance(listOf(ExercisePerformance("Махи", 16, 10, 3, true, "completed"))),
                    Instant.now().minusSeconds(86400),
                ),
                // без actualPerformance
                createWorkout(plan, null),
            )
        val context = WorkoutContext(profile, workouts, listOf(16, 24), 1, false)

        val prompt = aiService.buildWorkoutPrompt(context)

        val dateMatches = "\"date\":".toRegex().findAll(prompt).count()
        dateMatches shouldBe 2
    }

    // 1.5.5 Упражнение с нулевым объемом (reps=0 или sets=0) — не учитывается в total_volume_kg
    "1.5.5 Упражнение с нулевым объемом (reps=0 или sets=0) — не учитывается в total_volume_kg" {
        val profile = createProfile()
        val plan =
            createWorkoutPlan(
                listOf(
                    Exercise("Махи", 16, 10, 3, null, null),
                    // reps=0
                    Exercise("Пропущенное", 16, 0, 3, null, null),
                    // sets=0
                    Exercise("Пропущенное2", 16, 10, 0, null, null),
                    // weight=0
                    Exercise("Пропущенное3", 0, 10, 3, null, null),
                ),
            )
        val actual =
            createActualPerformance(
                listOf(
                    ExercisePerformance("Махи", 16, 10, 3, true, "completed"),
                    // reps=0
                    ExercisePerformance("Пропущенное", 16, 0, 3, false, "failed"),
                    // sets=0
                    ExercisePerformance("Пропущенное2", 16, 10, 0, false, "failed"),
                    // weight=0
                    ExercisePerformance("Пропущенное3", 0, 10, 3, false, "failed"),
                ),
            )
        val workout = createWorkout(plan, actual)
        val context = WorkoutContext(profile, listOf(workout), listOf(16, 24), 1, false)

        val prompt = aiService.buildWorkoutPrompt(context)
        val jsonElement = json.parseToJsonElement(prompt)
        val workoutJson = jsonElement.jsonObject["context"]?.jsonObject?.get("history")?.jsonArray?.get(0)?.jsonObject

        val expectedVolume = 480
        val totalVolume = workoutJson?.get("total_volume_kg")?.jsonPrimitive?.content?.toIntOrNull()
        totalVolume shouldBe expectedVolume

        val exercises = workoutJson?.get("exercises")?.jsonArray
        exercises shouldNotBe null
        exercises!!.size shouldBe 4
    }

    // 3.1.1 Сгенерированный промпт — валидный JSON
    "3.1.1 Сгенерированный промпт — валидный JSON" {
        val profile = createProfile()
        val plan = createWorkoutPlan()
        val actual =
            createActualPerformance(
                listOf(ExercisePerformance("Махи", 16, 10, 3, true, "completed")),
            )
        val workout = createWorkout(plan, actual)
        val context = WorkoutContext(profile, listOf(workout), listOf(16, 24), 1, false)

        val prompt = aiService.buildWorkoutPrompt(context)

        val jsonElement = json.parseToJsonElement(prompt)
        val jsonObject = jsonElement.jsonObject
        jsonObject shouldNotBe null
        jsonObject["context"] shouldNotBe null
    }

    // 3.1.2 Все числовые поля корректны (не null для обязательных)
    "3.1.2 Все числовые поля корректны (не null для обязательных)" {
        val profile = createProfile()
        val plan = createWorkoutPlan()
        val actual =
            createActualPerformance(
                listOf(ExercisePerformance("Махи", 16, 10, 3, true, "completed")),
                rpe = 7,
            )
        val workout = createWorkout(plan, actual)
        val context = WorkoutContext(profile, listOf(workout), listOf(16, 24), 1, false)

        val prompt = aiService.buildWorkoutPrompt(context)

        val jsonElement = json.parseToJsonElement(prompt)
        val jsonObject = jsonElement.jsonObject
        val contextObj = jsonObject["context"]?.jsonObject
        val athlete = contextObj?.get("athlete")?.jsonObject

        athlete?.get("weight")?.jsonPrimitive?.content?.toFloatOrNull() shouldNotBe null
        athlete?.get("weight")?.jsonPrimitive?.content?.toFloatOrNull() shouldBe 70f
    }

    // Дополнительный тест: проверка что planned_exercises формируется корректно
    "planned_exercises формируется корректно с правильными данными из плана" {
        val profile = createProfile()
        val plan =
            createWorkoutPlan(
                listOf(
                    Exercise("Махи", 16, 10, 3, null, null),
                    Exercise("Турецкий подъем", 24, 5, 2, null, null),
                ),
            )
        val actual =
            createActualPerformance(
                listOf(
                    ExercisePerformance("Махи", 16, 10, 3, true, "completed"),
                    ExercisePerformance("Турецкий подъем", 24, 5, 2, true, "completed"),
                ),
            )
        val workout = createWorkout(plan, actual)
        val context = WorkoutContext(profile, listOf(workout), listOf(16, 24), 1, false)

        val prompt = aiService.buildWorkoutPrompt(context)
        val jsonElement = json.parseToJsonElement(prompt)
        val plannedExercises =
            jsonElement.jsonObject["context"]?.jsonObject?.get("history")?.jsonArray?.get(0)
                ?.jsonObject?.get("planned_exercises")?.jsonPrimitive?.content

        plannedExercises shouldNotBe null
        plannedExercises!! shouldContain "Махи"
        plannedExercises shouldContain "16kg"
        plannedExercises shouldContain "10x3"
        plannedExercises shouldContain "Турецкий подъем"
        plannedExercises shouldContain "24kg"
        plannedExercises shouldContain "5x2"
    }

    // Дополнительный тест: проверка что данные из профиля корректно передаются
    "Данные профиля корректно передаются в промпт" {
        val profile =
            createProfile(
                weights = listOf(16, 24, 32),
                experience = ExperienceLevel.PRO,
                bodyWeight = 85.5f,
                gender = Gender.FEMALE,
                goal = "Выносливость",
            )
        val context = WorkoutContext(profile, emptyList(), listOf(16, 24, 32), 5, true)

        val prompt = aiService.buildWorkoutPrompt(context)
        val jsonElement = json.parseToJsonElement(prompt)
        val athlete = jsonElement.jsonObject["context"]?.jsonObject?.get("athlete")?.jsonObject
        val equipment = jsonElement.jsonObject["context"]?.jsonObject?.get("equipment")?.jsonObject

        athlete shouldNotBe null
        athlete!!["experience"]?.jsonPrimitive?.content shouldBe "PRO"
        athlete["weight"]?.jsonPrimitive?.content?.toFloatOrNull() shouldBe 85.5f
        athlete["gender"]?.jsonPrimitive?.content shouldBe "FEMALE"
        athlete["goal"]?.jsonPrimitive?.content shouldBe "Выносливость"

        equipment shouldNotBe null
        val availableWeights = equipment!!["available_kettlebells"]?.jsonArray
        availableWeights shouldNotBe null
        availableWeights!!.size shouldBe 3
        availableWeights[0].jsonPrimitive.content.toIntOrNull() shouldBe 16
        availableWeights[1].jsonPrimitive.content.toIntOrNull() shouldBe 24
        availableWeights[2].jsonPrimitive.content.toIntOrNull() shouldBe 32

        val contextObj = jsonElement.jsonObject["context"]?.jsonObject
        contextObj?.get("current_week")?.jsonPrimitive?.content?.toIntOrNull() shouldBe 5
        contextObj?.get("is_deload")?.jsonPrimitive?.content?.toBoolean() shouldBe true
    }
})
