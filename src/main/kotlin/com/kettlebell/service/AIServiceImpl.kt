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
            .joinToString("\n\n") { workout ->
                buildString {
                    append("Дата: ${workout.timing.completedAt}\n")
                    workout.actualPerformance?.let { perf ->
                        append("RPE: ${perf.rpe ?: "не указано"}\n")
                        append("Упражнения:\n")
                        perf.data.forEach { ex ->
                            append("- ${ex.name}: ${ex.weight}kg, ${ex.reps}×${ex.sets}, " +
                                "выполнено: ${if (ex.completed) "да" else "нет"}\n")
                        }
                        if (perf.issues.isNotEmpty()) {
                            append("Проблемы: ${perf.issues.joinToString(", ")}\n")
                        }
                    }
                }
            }
        
        return """
            Создай персонализированный план тренировки с гирями.
            
            Профиль атлета:
            - Опыт: ${profile.experience.name}
            - Вес тела: ${profile.bodyWeight}kg
            - Пол: ${profile.gender.name}
            - Цель: ${profile.goal}
            - Доступные гири: ${profile.weights.joinToString(", ")}kg
            
            История тренировок (последние ${context.recentWorkouts.size}):
            ${if (recentWorkoutsInfo.isNotEmpty()) recentWorkoutsInfo else "Нет завершенных тренировок"}
            
            Неделя тренировок: ${context.trainingWeek}
            
            Создай план в формате JSON:
            {
              "warmup": "разминка",
              "exercises": [
                {
                  "name": "название упражнения",
                  "weight": вес_в_кг,
                  "reps": количество_повторений_или_null,
                  "sets": количество_подходов_или_null,
                  "timeWork": время_работы_в_секундах_или_null,
                  "timeRest": время_отдыха_в_секундах_или_null
                }
              ],
              "cooldown": "заминка"
            }
            
            Учти историю тренировок для адаптации нагрузки.
        """.trimIndent()
    }
    
    private fun buildFeedbackAnalysisPrompt(feedback: String, originalPlan: WorkoutPlan): String {
        val exercisesInfo = originalPlan.exercises.joinToString("\n") { ex ->
            "- ${ex.name}: ${ex.weight}kg, " +
            if (ex.reps != null && ex.sets != null) {
                "${ex.reps}×${ex.sets}"
            } else {
                "Работа: ${ex.timeWork}с, Отдых: ${ex.timeRest}с"
            }
        }
        
        return """
            Проанализируй обратную связь о тренировке и извлеки структурированные данные.
            
            Оригинальный план:
            Разминка: ${originalPlan.warmup}
            Упражнения:
            $exercisesInfo
            Заминка: ${originalPlan.cooldown}
            
            Обратная связь пользователя:
            $feedback
            
            Верни JSON:
            {
              "data": [
                {
                  "name": "название упражнения",
                  "weight": вес_в_кг,
                  "reps": количество_повторений,
                  "sets": количество_подходов,
                  "completed": выполнено_ли_полностью
                }
              ],
              "rpe": оценка_нагрузки_1_10_или_null,
              "issues": ["проблема1", "проблема2"] или []
            }
            
            Если упражнение не упомянуто, используй данные из оригинального плана.
            Если упомянуты травмы или дискомфорт, добавь их в issues.
        """.trimIndent()
    }
    
    private fun parseWorkoutPlan(
        content: String,
        tokensUsed: Int,
        finishReason: String?,
        generationTime: Long
    ): WorkoutPlan {
        try {
            val jsonObject = json.parseToJsonElement(content).jsonObject
            val warmup = jsonObject["warmup"]?.jsonPrimitive?.content ?: ""
            val cooldown = jsonObject["cooldown"]?.jsonPrimitive?.content ?: ""
            val exercisesJson = jsonObject["exercises"]?.jsonObject ?: throw IllegalStateException("No exercises in response")
            
            val exercises = exercisesJson.entries.map { (_, value) ->
                val ex = value.jsonObject
                Exercise(
                    name = ex["name"]?.jsonPrimitive?.content ?: "",
                    weight = ex["weight"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    reps = ex["reps"]?.jsonPrimitive?.content?.toIntOrNull(),
                    sets = ex["sets"]?.jsonPrimitive?.content?.toIntOrNull(),
                    timeWork = ex["timeWork"]?.jsonPrimitive?.content?.toIntOrNull(),
                    timeRest = ex["timeRest"]?.jsonPrimitive?.content?.toIntOrNull()
                )
            }
            
            return WorkoutPlan(warmup = warmup, exercises = exercises, cooldown = cooldown)
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
            val jsonObject = json.parseToJsonElement(content).jsonObject
            val dataJson = jsonObject["data"]?.jsonObject ?: throw IllegalStateException("No data in response")
            
            val data = dataJson.entries.map { (_, value) ->
                val ex = value.jsonObject
                ExercisePerformance(
                    name = ex["name"]?.jsonPrimitive?.content ?: "",
                    weight = ex["weight"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    reps = ex["reps"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    sets = ex["sets"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    completed = ex["completed"]?.jsonPrimitive?.content?.toBoolean() ?: false
                )
            }
            
            val rpe = jsonObject["rpe"]?.jsonPrimitive?.content?.toIntOrNull()
            val issuesJson = jsonObject["issues"]?.jsonObject
            val issues = issuesJson?.entries?.map { it.value.jsonPrimitive.content } ?: emptyList()
            
            return ActualPerformance(
                rawFeedback = rawFeedback,
                data = data,
                rpe = rpe,
                issues = issues
            )
        } catch (e: Exception) {
            logger.error("Failed to parse actual performance: ${e.message}", e)
            throw IllegalStateException("Invalid actual performance format: ${e.message}", e)
        }
    }
    
    companion object {
        private const val SYSTEM_PROMPT_WORKOUT_GENERATION = """
            Ты эксперт по тренировкам с гирями. Создавай безопасные и эффективные планы тренировок 
            на основе профиля атлета и его истории тренировок. Адаптируй нагрузку в зависимости от 
            предыдущих результатов. Всегда возвращай валидный JSON без дополнительного текста.
        """
        
        private const val SYSTEM_PROMPT_FEEDBACK_ANALYSIS = """
            Ты анализируешь обратную связь о тренировке. Извлекай структурированные данные о 
            выполненных упражнениях, весах, повторениях, подходах. Определяй уровень нагрузки (RPE) 
            и выявляй упоминания травм или дискомфорта. Всегда возвращай валидный JSON без 
            дополнительного текста.
        """
    }
}
