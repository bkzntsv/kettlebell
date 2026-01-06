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
            
            logger.info("AI Response Content: $content")
            
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
        
        return buildString {
            append("Create a personalized kettlebell workout plan.\n\n")
            append("Athlete Profile:\n")
            append("- Experience: ${profile.experience.name}\n")
            append("- Body Weight: ${profile.bodyWeight}kg\n")
            append("- Gender: ${profile.gender.name}\n")
            append("- Goal: ${profile.goal}\n")
            append("- Available Kettlebells: ${profile.weights.joinToString(", ")}kg\n")
            append("CRITICAL: Use ONLY the available kettlebell weights listed above. Do not invent other weights.\n\n")
            
            append("Workout History (last ${context.recentWorkouts.size}):\n")
            append(if (recentWorkoutsInfo.isNotEmpty()) recentWorkoutsInfo else "No completed workouts")
            append("\n\n")
            
            append("Training Week: ${context.trainingWeek}\n\n")
            
            if (context.suggestDeload) {
                append("IMPORTANT: The user has been training with high intensity and stagnating volume recently. Generate a DELOAD workout (lower volume, focus on technique/mobility, RPE 5-6).\n\n")
            }
            
            append("Create a plan in JSON format:\n")
            append("{\n")
            append("  \"warmup\": \"warmup description\",\n")
            append("  \"exercises\": [\n")
            append("    {\n")
            append("      \"name\": \"exercise name\",\n")
            append("      \"weight\": weight_in_kg,\n")
            append("      \"reps\": reps_count_or_null,\n")
            append("      \"sets\": sets_count_or_null,\n")
            append("      \"timeWork\": work_time_seconds_or_null,\n")
            append("      \"timeRest\": rest_time_seconds_or_null\n")
            append("    }\n")
            append("  ],\n")
            append("  \"cooldown\": \"cooldown description\"\n")
            append("}")
        }
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
            val jsonObject = json.parseToJsonElement(cleanedContent).jsonObject
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
            val cleanedContent = cleanJsonContent(content)
            val jsonObject = json.parseToJsonElement(cleanedContent).jsonObject
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
            and training history. Adapt the load based on previous results. 
            ALWAYS reply in RUSSIAN language.
            Plan MUST contain at least 3 exercises.
            Always return valid JSON without additional text.
        """
        
        private const val SYSTEM_PROMPT_FEEDBACK_ANALYSIS = """
            You analyze workout feedback. Extract structured data about performed exercises, weights, reps, sets. 
            Determine the RPE (Rate of Perceived Exertion) and identify any mentions of injuries or discomfort. 
            Always return valid JSON without additional text.
        """
    }
}
