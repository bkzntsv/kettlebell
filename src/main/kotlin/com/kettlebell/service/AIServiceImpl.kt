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
                    append("Date: ${workout.timing.completedAt}\n")
                    workout.actualPerformance?.let { perf ->
                        append("RPE: ${perf.rpe ?: "not specified"}\n")
                        append("Exercises:\n")
                        perf.data.forEach { ex ->
                            append("- ${ex.name}: ${ex.weight}kg, ${ex.reps}×${ex.sets}, " +
                                "completed: ${if (ex.completed) "yes" else "no"}\n")
                        }
                        if (perf.issues.isNotEmpty()) {
                            append("Issues: ${perf.issues.joinToString(", ")}\n")
                        }
                    }
                }
            }
        
        return """
            Create a personalized kettlebell workout plan.
            
            Athlete Profile:
            - Experience: ${profile.experience.name}
            - Body Weight: ${profile.bodyWeight}kg
            - Gender: ${profile.gender.name}
            - Goal: ${profile.goal}
            - Available Kettlebells: ${profile.weights.joinToString(", ")}kg
            
            Workout History (last ${context.recentWorkouts.size}):
            ${if (recentWorkoutsInfo.isNotEmpty()) recentWorkoutsInfo else "No completed workouts"}
            
            Training Week: ${context.trainingWeek}
            
            Create a plan in JSON format:
            {
              "warmup": "warmup description",
              "exercises": [
                {
                  "name": "exercise name",
                  "weight": weight_in_kg,
                  "reps": reps_count_or_null,
                  "sets": sets_count_or_null,
                  "timeWork": work_time_seconds_or_null,
                  "timeRest": rest_time_seconds_or_null
                }
              ],
              "cooldown": "cooldown description"
            }
            
            Adapt the load based on workout history.
        """.trimIndent()
    }
    
    private fun buildFeedbackAnalysisPrompt(feedback: String, originalPlan: WorkoutPlan): String {
        val exercisesInfo = originalPlan.exercises.joinToString("\n") { ex ->
            "- ${ex.name}: ${ex.weight}kg, " +
            if (ex.reps != null && ex.sets != null) {
                "${ex.reps}×${ex.sets}"
            } else {
                "Work: ${ex.timeWork}s, Rest: ${ex.timeRest}s"
            }
        }
        
        return """
            Analyze workout feedback and extract structured data.
            
            Original Plan:
            Warmup: ${originalPlan.warmup}
            Exercises:
            $exercisesInfo
            Cooldown: ${originalPlan.cooldown}
            
            User Feedback:
            $feedback
            
            Return JSON:
            {
              "data": [
                {
                  "name": "exercise name",
                  "weight": weight_in_kg,
                  "reps": reps_count,
                  "sets": sets_count,
                  "completed": boolean
                }
              ],
              "rpe": rpe_1_10_or_null,
              "issues": ["issue1", "issue2"] or []
            }
            
            If an exercise is not mentioned, use data from the original plan.
            If injuries or discomfort are mentioned, add them to issues.
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
            val exercisesJson = jsonObject["exercises"]?.jsonArray ?: throw IllegalStateException("No exercises in response")
            
            val exercises = exercisesJson.map { value ->
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
            val dataJson = jsonObject["data"]?.jsonArray ?: throw IllegalStateException("No data in response")
            
            val data = dataJson.map { value ->
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
            val issuesJson = jsonObject["issues"]?.jsonArray
            val issues = issuesJson?.map { it.jsonPrimitive.content } ?: emptyList()
            
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
            You are an expert kettlebell coach. Create safe and effective workout plans based on the athlete's profile 
            and training history. Adapt the load based on previous results. Always return valid JSON without additional text.
        """
        
        private const val SYSTEM_PROMPT_FEEDBACK_ANALYSIS = """
            You analyze workout feedback. Extract structured data about performed exercises, weights, reps, sets. 
            Determine the RPE (Rate of Perceived Exertion) and identify any mentions of injuries or discomfort. 
            Always return valid JSON without additional text.
        """
    }
}
