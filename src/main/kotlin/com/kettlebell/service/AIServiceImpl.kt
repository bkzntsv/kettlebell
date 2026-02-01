package com.kettlebell.service

import com.aallam.openai.api.audio.TranscriptionRequest
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.file.FileSource
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.kettlebell.config.AppConfig
import com.kettlebell.model.AILog
import com.kettlebell.model.ActualPerformance
import com.kettlebell.model.Exercise
import com.kettlebell.model.ExercisePerformance
import com.kettlebell.model.WorkoutContext
import com.kettlebell.model.WorkoutPlan
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.source
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream

class AIServiceImpl(
    private val openAIClient: OpenAI,
    private val config: AppConfig,
) : AIService {
    private val logger = LoggerFactory.getLogger(AIServiceImpl::class.java)
    private val gptModel = ModelId("gpt-5-mini")
    private val whisperModel = ModelId("whisper-1")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Правильно экранирует строку для вставки в JSON.
     * Обрабатывает двойные кавычки, обратные слеши, и управляющие символы.
     */
    private fun escapeJsonString(value: String): String {
        return buildString(value.length) {
            value.forEach { char ->
                when (char) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    else -> {
                        if (char.code in 0x00..0x1F) {
                            append("\\u${char.code.toString(16).padStart(4, '0')}")
                        } else {
                            append(char)
                        }
                    }
                }
            }
        }
    }

    override suspend fun generateWorkoutPlan(context: WorkoutContext): WorkoutPlan {
        return withRetry(maxAttempts = 3) {
            val startTime = System.currentTimeMillis()
            val prompt = buildWorkoutPrompt(context)

            val request =
                ChatCompletionRequest(
                    model = gptModel,
                    messages =
                        listOf(
                            ChatMessage(
                                role = ChatRole.System,
                                content = SYSTEM_PROMPT_WORKOUT_GENERATION,
                            ),
                            ChatMessage(
                                role = ChatRole.User,
                                content = prompt,
                            ),
                        ),
                )

            val response = openAIClient.chatCompletion(request)
            val choice = response.choices.first()
            val content = choice.message.content ?: throw IllegalStateException("Empty response from AI")
            val finishReason = choice.finishReason?.toString()
            val tokensUsed = response.usage?.totalTokens ?: 0

            logger.info("AI Response Content (raw): $content")

            logger.info(
                "Workout plan generated: tokens=$tokensUsed, finishReason=$finishReason, " +
                    "time=${System.currentTimeMillis() - startTime}ms",
            )

            parseWorkoutPlan(content, tokensUsed, finishReason, System.currentTimeMillis() - startTime)
        }
    }

    override suspend fun transcribeVoice(audioFile: ByteArray): String {
        return withRetry(maxAttempts = 3) {
            val request =
                TranscriptionRequest(
                    audio = FileSource(name = "voice.ogg", source = ByteArrayInputStream(audioFile).source()),
                    model = whisperModel,
                    language = "ru",
                )

            val response = openAIClient.transcription(request)
            response.text
        }
    }

    override suspend fun analyzeFeedback(
        feedback: String,
        originalPlan: WorkoutPlan,
    ): ActualPerformance {
        return withRetry(maxAttempts = 3) {
            val startTime = System.currentTimeMillis()
            val prompt = buildFeedbackAnalysisPrompt(feedback, originalPlan)

            val request =
                ChatCompletionRequest(
                    model = gptModel,
                    messages =
                        listOf(
                            ChatMessage(
                                role = ChatRole.System,
                                content = SYSTEM_PROMPT_FEEDBACK_ANALYSIS,
                            ),
                            ChatMessage(
                                role = ChatRole.User,
                                content = prompt,
                            ),
                        ),
                )

            val response = openAIClient.chatCompletion(request)
            val choice = response.choices.first()
            val content = choice.message.content ?: throw IllegalStateException("Empty response from AI")
            val finishReason = choice.finishReason?.toString()
            val tokensUsed = response.usage?.totalTokens ?: 0

            logger.info("AI Feedback Analysis Response (raw): $content")
            logger.info(
                "Feedback analyzed: tokens=$tokensUsed, finishReason=$finishReason, " +
                    "time=${System.currentTimeMillis() - startTime}ms",
            )

            parseActualPerformance(content, feedback, tokensUsed, finishReason, System.currentTimeMillis() - startTime)
        }
    }

    private suspend fun <T> withRetry(
        maxAttempts: Int = 3,
        initialDelay: Long = 1000,
        factor: Double = 2.0,
        block: suspend () -> T,
    ): T {
        var currentDelay = initialDelay
        var lastException: Exception? = null

        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                logger.warn("AI service attempt ${attempt + 1} failed: ${e.message}", e)

                if (attempt < maxAttempts - 1) {
                    delay(currentDelay)
                    currentDelay = (currentDelay * factor).toLong()
                }
            }
        }

        throw lastException ?: IllegalStateException("All retry attempts failed")
    }

    internal fun buildWorkoutPrompt(context: WorkoutContext): String {
        val profile = context.profile.profile
        val recentWorkoutsInfo =
            context.recentWorkouts
                .filter { it.actualPerformance != null }
                .take(3)
                .joinToString(",\n") { workout ->
                    val perf = workout.actualPerformance!!
                    val plan = workout.plan

                    val totalVolume =
                        perf.data.sumOf { ex ->
                            if (ex.weight > 0 && ex.reps > 0 && ex.sets > 0) {
                                ex.weight * ex.reps * ex.sets
                            } else {
                                0
                            }
                        }

                    val exercisesDetail =
                        perf.data.mapIndexed { index, actual ->
                            val planned = plan.exercises.getOrNull(index)
                            val plannedWeight = planned?.weight ?: 0
                            val plannedReps = planned?.reps
                            val plannedSets = planned?.sets
                            val plannedTimeWork = planned?.timeWork
                            val plannedTimeRest = planned?.timeRest

                            val statusInfo =
                                if (actual.completed) {
                                    if (actual.status == "completed") "completed" else actual.status ?: "completed"
                                } else {
                                    actual.status ?: "failed"
                                }

                            val nameEscaped = escapeJsonString(actual.name)

                            """
                            {
                              "name": "$nameEscaped",
                              "planned_weight_kg": $plannedWeight,
                              "planned_reps": ${plannedReps ?: "null"},
                              "planned_sets": ${plannedSets ?: "null"},
                              "planned_time_work_sec": ${plannedTimeWork ?: "null"},
                              "planned_time_rest_sec": ${plannedTimeRest ?: "null"},
                              "actual_weight_kg": ${actual.weight},
                              "actual_reps": ${actual.reps},
                              "actual_sets": ${actual.sets},
                              "status": "$statusInfo"
                            }
                            """.trimIndent()
                        }.joinToString(",\n")

                    val issuesStr =
                        if (perf.issues.isNotEmpty()) {
                            perf.issues.map { escapeJsonString(it) }.joinToString("\", \"", "\"", "\"")
                        } else {
                            ""
                        }
                    val technicalNotes = perf.technicalNotes?.takeIf { it.isNotBlank() }?.let { escapeJsonString(it) } ?: ""
                    val recoveryStatus = perf.recoveryStatus?.takeIf { it.isNotBlank() }?.let { escapeJsonString(it) } ?: ""

                    val plannedExercisesStr =
                        plan.exercises.joinToString(", ") { ex ->
                            val repsSets =
                                if (ex.reps != null && ex.sets != null) {
                                    "${ex.reps}x${ex.sets}"
                                } else if (ex.timeWork != null && ex.timeRest != null) {
                                    "${ex.timeWork}s/${ex.timeRest}s"
                                } else {
                                    ""
                                }
                            "${escapeJsonString(ex.name)} ${ex.weight}kg $repsSets".trim()
                        }

                    """
                    {
                      "date": "${workout.timing.completedAt}",
                      "planned_exercises": "$plannedExercisesStr",
                      "exercises": [
                        $exercisesDetail
                      ],
                      "total_volume_kg": $totalVolume,
                      "rpe": ${perf.rpe ?: "null"},
                      "recovery_status": ${if (recoveryStatus.isNotEmpty()) "\"$recoveryStatus\"" else "null"},
                      "red_flags": [${if (perf.issues.isNotEmpty()) issuesStr else ""}],
                      "technical_notes": ${if (technicalNotes.isNotEmpty()) "\"$technicalNotes\"" else "null"}
                    }
                    """.trimIndent()
                }

        return """
            {
              "context": {
                "athlete": {
                  "experience": "${profile.experience.name}",
                  "weight": ${profile.bodyWeight},
                  "gender": "${profile.gender.name}",
                  "goal": "${escapeJsonString(profile.goal.displayName())}"
                },
                "equipment": {
                  "available_kettlebells": [${profile.weights.joinToString(", ")}]
                },
                "history": [
                  $recentWorkoutsInfo
                ],
                "current_week": ${context.trainingWeek},
                "is_deload": ${context.suggestDeload} 
              },
              "instructions": "Create a personalized workout plan aligned with the athlete's goal. If is_deload = true, reduce volume by 40% and focus on mobility. CRITICAL: Use ONLY weights from available_kettlebells - never suggest intermediate weights. If a weight seems too heavy, adjust reps/sets/tempo instead. Add brief technique tips (coaching_tips) for each exercise. Keep warmup and cooldown short, simple, and varied. IMPORTANT: Analyze workout history carefully - compare planned vs actual performance to understand athlete's capabilities. Use total_volume_kg, RPE, recovery_status, and exercise completion status to adjust progression. Pay attention to technical_notes and red_flags to address issues. If athlete consistently fails to complete planned reps/sets, reduce volume or adjust intensity. If athlete easily completes all sets with low RPE, gradually increase volume or intensity."
            }
            """.trimIndent()
    }

    private fun buildFeedbackAnalysisPrompt(
        feedback: String,
        originalPlan: WorkoutPlan,
    ): String {
        val exercisesInfo =
            originalPlan.exercises.joinToString(", ") { ex ->
                "${ex.name}: ${ex.weight}kg, ${if (ex.reps != null) "${ex.reps}x${ex.sets}" else "${ex.timeWork}s work"}"
            }

        return """
            Сравни запланированную тренировку и отзыв пользователя.
            План:
            Warmup: ${originalPlan.warmup}
            Exercises: [$exercisesInfo]
            Cooldown: ${originalPlan.cooldown}

            Отзыв пользователя: 
            "$feedback"

            КРИТИЧЕСКИ ВАЖНО: Ответ ТОЛЬКО в формате JSON, без дополнительного текста. Структура:
            {
              "actual_data": [
                { 
                  "name": "точное_название_упражнения_из_плана", 
                  "weight": число_в_кг, 
                  "reps": число_повторов, 
                  "sets": число_подходов, 
                  "status": "completed" 
                }
              ],
              "rpe": число_от_1_до_10,
              "recovery_status": "good/fatigued/injured",
              "technical_notes": "краткий вывод о технике на основе слов пользователя",
              "red_flags": ["список жалоб на боль или дискомфорт"],
              "coach_feedback": "Твой ответ атлету"
            }
            
            ВАЖНО: Для каждого упражнения из плана ДОЛЖЕН быть объект в actual_data с реальными значениями weight, reps, sets.
            
            ПРАВИЛА ПОДСЧЕТА (строго следуй им):
            1. Двуручные упражнения (Swing, Goblet Squat): sets = количество подходов. НЕ УМНОЖАЙ НА 2.
            2. Односторонние упражнения (One Arm Press/Row/Lunge): sets = СУММА подходов на обе стороны (например, план 3x5 на сторону -> sets=6, reps=5).
            3. Упражнения на время (Carry, Plank): reps = время выполнения в секундах, sets = количество подходов.
            4. EMOM: sets = количество раундов (минут), reps = количество повторений в минуту.
            
            Если пользователь не указал конкретные числа, используй значения из плана, применяя эти правила.
            """.trimIndent()
    }

    private fun cleanJsonContent(content: String): String {
        val startIndex = content.indexOf('{')
        val endIndex = content.lastIndexOf('}')

        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            return content.substring(startIndex, endIndex + 1)
        }

        return content.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private fun parseWorkoutPlan(
        content: String,
        tokensUsed: Int,
        finishReason: String?,
        generationTime: Long,
    ): WorkoutPlan {
        try {
            val cleanedContent = cleanJsonContent(content)
            logger.debug("Cleaned JSON content: $cleanedContent")
            val jsonObject = json.parseToJsonElement(cleanedContent).jsonObject
            logger.debug("Parsed JSON keys: ${jsonObject.keys}")

            val warmup = jsonObject["warmup"]?.jsonPrimitive?.content ?: ""
            val cooldown = jsonObject["cooldown"]?.jsonPrimitive?.content ?: ""
            val exercisesJson = jsonObject["exercises"]?.jsonArray

            if (exercisesJson == null) {
                logger.error("No 'exercises' field found. Available keys: ${jsonObject.keys}")
                logger.error("Full JSON structure: $jsonObject")
                throw IllegalStateException("No exercises in response")
            }

            val exercises =
                exercisesJson.map { value ->
                    val ex = value.jsonObject
                    Exercise(
                        name = ex["name"]?.jsonPrimitive?.content ?: "",
                        weight = ex["weight"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        reps = ex["reps"]?.jsonPrimitive?.content?.toIntOrNull(),
                        sets = ex["sets"]?.jsonPrimitive?.content?.toIntOrNull(),
                        timeWork = ex["timeWork"]?.jsonPrimitive?.content?.toIntOrNull(),
                        timeRest = ex["timeRest"]?.jsonPrimitive?.content?.toIntOrNull(),
                        coachingTips = ex["coaching_tips"]?.jsonPrimitive?.content,
                    )
                }

            val aiLog =
                AILog(
                    tokensUsed = tokensUsed,
                    modelVersion = gptModel.id,
                    planGenerationTime = generationTime,
                    feedbackAnalysisTime = null,
                    finishReason = finishReason,
                )

            return WorkoutPlan(warmup = warmup, exercises = exercises, cooldown = cooldown, aiLog = aiLog)
        } catch (e: Exception) {
            logger.error("Failed to parse workout plan: ${e.message}", e)
            throw IllegalStateException("Invalid workout plan format: ${e.message}", e)
        }
    }

    private fun parseActualPerformance(
        content: String,
        rawFeedback: String,
        tokensUsed: Int,
        finishReason: String?,
        analysisTime: Long,
    ): ActualPerformance {
        try {
            val cleanedContent = cleanJsonContent(content)
            logger.debug("Cleaned feedback JSON content: $cleanedContent")
            val jsonObject = json.parseToJsonElement(cleanedContent).jsonObject
            logger.debug("Parsed feedback JSON keys: ${jsonObject.keys}")

            val dataJson = jsonObject["actual_data"]?.jsonArray
            if (dataJson == null) {
                logger.error("No 'actual_data' field found. Available keys: ${jsonObject.keys}")
                logger.error("Full JSON structure: $jsonObject")
                throw IllegalStateException("No actual_data in response")
            }

            val data =
                dataJson.map { value ->
                    val ex = value.jsonObject
                    val status = ex["status"]?.jsonPrimitive?.content
                    val completed = status == "completed" || status == "partial"

                    val name = ex["name"]?.jsonPrimitive?.content ?: ""
                    val weight = ex["weight"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val reps = ex["reps"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val sets = ex["sets"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

                    logger.debug(
                        "Parsed exercise: name=$name, weight=$weight, reps=$reps, sets=$sets, completed=$completed, status=$status",
                    )

                    ExercisePerformance(
                        name = name,
                        weight = weight,
                        reps = reps,
                        sets = sets,
                        completed = completed,
                        status = status,
                    )
                }

            logger.info(
                "Parsed ${data.size} exercises from feedback. " +
                    "Total volume calculation: ${data.sumOf { if (it.completed) it.weight * it.reps * it.sets else 0 }}",
            )

            val rpe = jsonObject["rpe"]?.jsonPrimitive?.content?.toIntOrNull()
            val issuesJson = jsonObject["red_flags"]?.jsonArray
            val issues = issuesJson?.map { it.jsonPrimitive.content } ?: emptyList()

            val recoveryStatus = jsonObject["recovery_status"]?.jsonPrimitive?.content
            val technicalNotes = jsonObject["technical_notes"]?.jsonPrimitive?.content
            val coachFeedback = jsonObject["coach_feedback"]?.jsonPrimitive?.content

            logger.info(
                "Parsed feedback analysis: rpe=$rpe, recoveryStatus=$recoveryStatus, technicalNotes=${technicalNotes?.take(
                    100,
                )}, issues=$issues, coachFeedback=${coachFeedback?.take(100)}",
            )

            val aiLog =
                AILog(
                    tokensUsed = tokensUsed,
                    modelVersion = gptModel.id,
                    planGenerationTime = 0,
                    feedbackAnalysisTime = analysisTime,
                    finishReason = finishReason,
                )

            val performance =
                ActualPerformance(
                    rawFeedback = rawFeedback,
                    data = data,
                    rpe = rpe,
                    issues = issues,
                    recoveryStatus = recoveryStatus,
                    technicalNotes = technicalNotes,
                    coachFeedback = coachFeedback,
                    aiLog = aiLog,
                )

            logger.info(
                "Created ActualPerformance: recoveryStatus=${performance.recoveryStatus}, technicalNotes=${performance.technicalNotes?.take(
                    50,
                )}, coachFeedback=${performance.coachFeedback?.take(50)}",
            )

            return performance
        } catch (e: Exception) {
            logger.error("Failed to parse actual performance: ${e.message}", e)
            throw IllegalStateException("Invalid actual performance format: ${e.message}", e)
        }
    }

    companion object {
        private const val SYSTEM_PROMPT_WORKOUT_GENERATION = """
            You are an elite kettlebell coach (StrongFirst/Hardstyle system). Your primary goal is to be an effective coach helping the athlete achieve their specific training goal. Create safe, highly effective programs using evidence-based methods: periodization, RPE management, and intelligent exercise selection.
            
            Your principles:
            Goal-oriented training: Design workouts that directly support the athlete's stated goal. Analyze their training history and adapt exercises, volume, and intensity accordingly. Don't rigidly force specific movement patterns - choose exercises that best serve the athlete's progress toward their goal.
            Weight management: The athlete only has specific kettlebells available. You MUST use ONLY weights from the available_kettlebells list. NEVER suggest reducing weight by a few kilograms - if a weight is too heavy, use intensity techniques (slower tempo, eccentric phases, fewer reps) or density methods (EMOM, time under tension). If a weight is too light, increase density or volume.
            Progression: Analyze training history. If the previous workout was successful, gradually increase volume (+1-2 reps or +1 set) rather than just weight. Adapt based on RPE and recovery status.
            Safety: For beginners, avoid complex snatches. Focus on shoulder stability and neutral spine. Pay attention to red flags from previous workouts.
            
            Warmup and cooldown:
            - Keep them SHORT and SIMPLE (2-3 exercises max, 1-2 sentences each)
            - Use PLAIN, accessible language - avoid complex technical terms
            - Vary exercises between workouts to keep it fresh
            - Warmup: Focus on mobility and activation relevant to the main workout
            - Cooldown: Focus on gentle stretching and recovery
            
            LANGUAGE: All text content in your response (warmup, exercise names, coaching_tips, cooldown) must be in RUSSIAN. This is the default language for user communication. The prompt is in English for clarity, but your output must always be in Russian.
            
            CRITICALLY IMPORTANT: Response ONLY in JSON format, no additional text. Structure:
            {
              "warmup": "brief warmup text in Russian (2-3 exercises, simple language)",
              "exercises": [
                {
                  "name": "exercise name in Russian",
                  "weight": number_in_kg (MUST be from available_kettlebells list),
                  "reps": number_of_reps_or_null,
                  "sets": number_of_sets_or_null,
                  "timeWork": seconds_of_work_or_null,
                  "timeRest": seconds_of_rest_or_null,
                  "coaching_tips": "brief technique tip in Russian"
                }
              ],
              "cooldown": "brief cooldown text in Russian (2-3 exercises, simple language)"
            }
        """

        private const val SYSTEM_PROMPT_FEEDBACK_ANALYSIS = """
            You are a sports data analyst and experienced coach (StrongFirst).
            Your tasks:
            1. Translate the athlete's free-form feedback into structured metrics.
            2. Generate the coach's response ("coach_feedback").
            
            Coach feedback tone: Adapt to the user's communication style. Be lively and human.
            - If the user writes briefly and dryly — respond equally clearly and to the point.
            - If the user is emotional, jokes, or uses slang — match that tone, but remain in the coach role.
            - React to context: encourage successes, empathize with fatigue, but always guide toward the goal.
            - If there's injury/pain: show care and professional caution.
            
            CRITICAL RULES FOR COACH FEEDBACK:
            - NEVER ask questions to the user
            - NEVER suggest the user to write or contact you
            - Provide a complete, self-contained response - a statement or comment from the coach
            - The feedback should be a closing remark, not an invitation for further conversation
            - All feedback must be in RUSSIAN (default user language)
            
            Critical for analytics:
            Identify pain markers (lower back, elbows, shoulders).
            Assess "Technical Failure" (if user writes that 'technique broke down').
            Compare plan vs actual. If reps are less than planned — record shortfall.
            Determine RPE (scale 1-10) based on emotional tone if number not explicitly stated.
            
            Response ONLY in JSON format.
        """
    }
}
