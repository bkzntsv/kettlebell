package com.kettlebell.service

import com.kettlebell.model.*
import com.aallam.openai.client.OpenAI
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.api.audio.TranscriptionRequest
import com.aallam.openai.api.file.FileSource
import com.kettlebell.config.AppConfig
import okio.source
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream

class AIServiceImpl(
    private val openAIClient: OpenAI,
    private val config: AppConfig
) : AIService {
    
    private val logger = LoggerFactory.getLogger(AIServiceImpl::class.java)
    private val gptModel = ModelId("gpt-4o")
    private val whisperModel = ModelId("whisper-1")
    private val json = Json { ignoreUnknownKeys = true }
    
    override suspend fun generateWorkoutPlan(context: WorkoutContext): WorkoutPlan {
        return withRetry(maxAttempts = 3) {
            val startTime = System.currentTimeMillis()
            val prompt = buildWorkoutPrompt(context)
            
            val request = ChatCompletionRequest(
                model = gptModel,
                messages = listOf(
                    ChatMessage(
                        role = ChatRole.System,
                        content = SYSTEM_PROMPT_WORKOUT_GENERATION
                    ),
                    ChatMessage(
                        role = ChatRole.User,
                        content = prompt
                    )
                ),
                // Response format omitted for compatibility
                // responseFormat = com.aallam.openai.api.chat.ResponseFormat.JsonObject
            )
            
            val response = openAIClient.chatCompletion(request)
            val choice = response.choices.first()
            val content = choice.message.content ?: throw IllegalStateException("Empty response from AI")
            val finishReason = choice.finishReason?.toString()
            val tokensUsed = response.usage?.totalTokens ?: 0
            
            logger.info("AI Response Content (raw): $content")
            
            logger.info(
                "Workout plan generated: tokens=$tokensUsed, finishReason=$finishReason, " +
                "time=${System.currentTimeMillis() - startTime}ms"
            )
            
            parseWorkoutPlan(content, tokensUsed, finishReason, System.currentTimeMillis() - startTime)
        }
    }
    
    override suspend fun transcribeVoice(audioFile: ByteArray): String {
        return withRetry(maxAttempts = 3) {
            val request = TranscriptionRequest(
                audio = FileSource("voice.ogg", ByteArrayInputStream(audioFile).source()),
                model = whisperModel,
                language = "ru"
            )
            
            val response = openAIClient.transcription(request)
            response.text
        }
    }
    
    override suspend fun analyzeFeedback(
        feedback: String,
        originalPlan: WorkoutPlan
    ): ActualPerformance {
        return withRetry(maxAttempts = 3) {
            val startTime = System.currentTimeMillis()
            val prompt = buildFeedbackAnalysisPrompt(feedback, originalPlan)
            
            val request = ChatCompletionRequest(
                model = gptModel,
                messages = listOf(
                    ChatMessage(
                        role = ChatRole.System,
                        content = SYSTEM_PROMPT_FEEDBACK_ANALYSIS
                    ),
                    ChatMessage(
                        role = ChatRole.User,
                        content = prompt
                    )
                ),
                // Response format omitted for compatibility
                // responseFormat = com.aallam.openai.api.chat.ResponseFormat.JsonObject
            )
            
            val response = openAIClient.chatCompletion(request)
            val choice = response.choices.first()
            val content = choice.message.content ?: throw IllegalStateException("Empty response from AI")
            val finishReason = choice.finishReason?.toString()
            val tokensUsed = response.usage?.totalTokens ?: 0
            
            logger.info("AI Feedback Analysis Response (raw): $content")
            logger.info(
                "Feedback analyzed: tokens=$tokensUsed, finishReason=$finishReason, " +
                "time=${System.currentTimeMillis() - startTime}ms"
            )
            
            parseActualPerformance(content, feedback, tokensUsed, finishReason, System.currentTimeMillis() - startTime)
        }
    }
    
    private suspend fun <T> withRetry(
        maxAttempts: Int = 3,
        initialDelay: Long = 1000,
        factor: Double = 2.0,
        block: suspend () -> T
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
    
    private fun buildWorkoutPrompt(context: WorkoutContext): String {
        val profile = context.profile.profile
        val recentWorkoutsInfo = context.recentWorkouts
            .filter { it.actualPerformance != null }
            .take(3)
            .joinToString(",\n") { workout ->
                val perf = workout.actualPerformance!!
                val exercisesStr = perf.data.joinToString(", ") { ex ->
                    "${ex.name} (${ex.weight}kg)"
                }
                val issuesStr = if (perf.issues.isNotEmpty()) perf.issues.joinToString("\", \"", "\"", "\"") else ""
                
                """
                {
                  "date": "${workout.timing.completedAt}",
                  "exercises": "$exercisesStr",
                  "rpe": ${perf.rpe ?: "null"},
                  "red_flags": [${if (perf.issues.isNotEmpty()) issuesStr else ""}]
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
                  "goal": "${profile.goal}"
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
              "instructions": "Создай персонализированный план. Если is_deload = true, снизь объем на 40% и сфокусируйся на мобильности. Используй ТОЛЬКО веса из available_kettlebells. Добавь краткие рекомендации по технике (coaching_tips) для каждого упражнения."
            }
        """.trimIndent()
    }
    
    private fun buildFeedbackAnalysisPrompt(feedback: String, originalPlan: WorkoutPlan): String {
        val exercisesInfo = originalPlan.exercises.joinToString(", ") { ex ->
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
            
            ВАЖНО: Для каждого упражнения из плана ДОЛЖЕН быть объект в actual_data с реальными значениями weight, reps, sets. Если пользователь не указал конкретные числа, используй значения из плана, но установи status в "partial" или "failed" если упражнение не выполнено полностью.
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
        generationTime: Long
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
            
            val exercises = exercisesJson.map { value ->
                val ex = value.jsonObject
                Exercise(
                    name = ex["name"]?.jsonPrimitive?.content ?: "",
                    weight = ex["weight"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    reps = ex["reps"]?.jsonPrimitive?.content?.toIntOrNull(),
                    sets = ex["sets"]?.jsonPrimitive?.content?.toIntOrNull(),
                    timeWork = ex["timeWork"]?.jsonPrimitive?.content?.toIntOrNull(),
                    timeRest = ex["timeRest"]?.jsonPrimitive?.content?.toIntOrNull(),
                    coachingTips = ex["coaching_tips"]?.jsonPrimitive?.content
                )
            }
            
            val aiLog = AILog(
                tokensUsed = tokensUsed,
                modelVersion = gptModel.id,
                planGenerationTime = generationTime,
                feedbackAnalysisTime = null,
                finishReason = finishReason
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
        analysisTime: Long
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
            
            val data = dataJson.map { value ->
                val ex = value.jsonObject
                val status = ex["status"]?.jsonPrimitive?.content
                // Mapping status to boolean for backward compatibility
                val completed = status == "completed" || status == "partial" 
                
                val name = ex["name"]?.jsonPrimitive?.content ?: ""
                val weight = ex["weight"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val reps = ex["reps"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val sets = ex["sets"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                
                logger.debug("Parsed exercise: name=$name, weight=$weight, reps=$reps, sets=$sets, completed=$completed, status=$status")
                
                ExercisePerformance(
                    name = name,
                    weight = weight,
                    reps = reps,
                    sets = sets,
                    completed = completed,
                    status = status
                )
            }
            
            logger.info("Parsed ${data.size} exercises from feedback. Total volume calculation: ${data.sumOf { if (it.completed) it.weight * it.reps * it.sets else 0 }}")
            
            val rpe = jsonObject["rpe"]?.jsonPrimitive?.content?.toIntOrNull()
            val issuesJson = jsonObject["red_flags"]?.jsonArray
            val issues = issuesJson?.map { it.jsonPrimitive.content } ?: emptyList()
            
            val recoveryStatus = jsonObject["recovery_status"]?.jsonPrimitive?.content
            val technicalNotes = jsonObject["technical_notes"]?.jsonPrimitive?.content
            val coachFeedback = jsonObject["coach_feedback"]?.jsonPrimitive?.content
            
            logger.info("Parsed feedback analysis: rpe=$rpe, recoveryStatus=$recoveryStatus, technicalNotes=${technicalNotes?.take(100)}, issues=$issues, coachFeedback=${coachFeedback?.take(100)}")
            
            val aiLog = AILog(
                tokensUsed = tokensUsed,
                modelVersion = gptModel.id,
                planGenerationTime = 0, // Not applicable here
                feedbackAnalysisTime = analysisTime,
                finishReason = finishReason
            )
            
            val performance = ActualPerformance(
                rawFeedback = rawFeedback,
                data = data,
                rpe = rpe,
                issues = issues,
                recoveryStatus = recoveryStatus,
                technicalNotes = technicalNotes,
                coachFeedback = coachFeedback,
                aiLog = aiLog
            )
            
            logger.info("Created ActualPerformance: recoveryStatus=${performance.recoveryStatus}, technicalNotes=${performance.technicalNotes?.take(50)}, coachFeedback=${performance.coachFeedback?.take(50)}")
            
            return performance
        } catch (e: Exception) {
            logger.error("Failed to parse actual performance: ${e.message}", e)
            throw IllegalStateException("Invalid actual performance format: ${e.message}", e)
        }
    }
    
    companion object {
        private const val SYSTEM_PROMPT_WORKOUT_GENERATION = """
            Вы — элитный тренер по гиревому спорту (система StrongFirst/Hardstyle). Ваша цель — создавать безопасные, высокоэффективные программы, используя доказательные методики: периодизацию, управление RPE и баланс паттернов движения.
            Ваши принципы:
            Баланс паттернов: Каждая тренировка должна включать: Hip Hinge (махи/тяги), Squat (приседы), Push (жимы), Pull (тяги в наклоне).
            Управление весом: Если доступный вес гири слишком велик для повторений, используйте методы деградации (снижение темпа, эксцентрические фазы). Если мал — увеличивайте плотность (EMOM) или время под нагрузкой.
            Прогрессия: Анализируйте историю. Если прошлая тренировка была успешной, увеличивайте объем (+1-2 повторения или +1 подход), а не только вес.
            Безопасность: Для новичков исключите сложные рывки. Фокус на стабильности плеча и нейтральной спине.
            
            КРИТИЧЕСКИ ВАЖНО: Ответ ТОЛЬКО в формате JSON, без дополнительного текста. Структура:
            {
              "warmup": "текст разминки",
              "exercises": [
                {
                  "name": "название упражнения",
                  "weight": число_в_кг,
                  "reps": число_повторов_или_null,
                  "sets": число_подходов_или_null,
                  "timeWork": секунды_работы_или_null,
                  "timeRest": секунды_отдыха_или_null,
                  "coaching_tips": "совет по технике"
                }
              ],
              "cooldown": "текст заминки"
            }
            Язык — РУССКИЙ.
        """
        
        private const val SYSTEM_PROMPT_FEEDBACK_ANALYSIS = """
            Вы — аналитик спортивных данных и опытный тренер (StrongFirst).
            Ваша задача:
            1. Перевести свободный отзыв атлета в структурированные метрики.
            2. Сгенерировать ответ тренера ("coach_feedback").
            
            Тон ответа тренера: Адаптируйся под стиль общения пользователя. Будь живым и человечным.
            - Если пользователь пишет кратко и сухо — отвечай так же четко и по делу.
            - Если пользователь эмоционален, шутит или использует сленг — поддержи этот тон, но оставайся в роли тренера.
            - Реагируй на контекст: подбадривай при успехах, сочувствуй при усталости, но всегда направляй к цели.
            - Если есть травма/боль: прояви заботу и профессиональную осторожность.
            
            Критически важно для аналитики:
            Выявляйте маркеры боли (поясница, локти, плечи).
            Оценивайте "Technical Failure" (если пользователь пишет, что 'техника поплыла').
            Сравнивайте план и факт. Если количество повторений меньше плана — фиксируйте недобор.
            Определяйте RPE (шкала 1-10) на основе эмоционального окраса текста, если число не указано явно.
            Ответ ТОЛЬКО в JSON.
        """
    }
}
