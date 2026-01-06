package com.kettlebell.bot

import com.kettlebell.config.AppConfig
import com.kettlebell.model.UserState
import com.kettlebell.service.FSMManager
import com.kettlebell.service.ProfileService
import com.kettlebell.service.WorkoutService
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class TelegramUpdate(
    val update_id: Long,
    val message: TelegramMessage? = null,
    val callback_query: TelegramCallbackQuery? = null
)

@Serializable
data class TelegramMessage(
    val message_id: Long,
    val from: TelegramUser,
    val chat: TelegramChat,
    val text: String? = null,
    val voice: TelegramVoice? = null
)

@Serializable
data class TelegramUser(
    val id: Long,
    val first_name: String,
    val username: String? = null
)

@Serializable
data class TelegramChat(
    val id: Long,
    val type: String
)

@Serializable
data class TelegramVoice(
    val file_id: String,
    val duration: Int? = null
)

@Serializable
data class TelegramCallbackQuery(
    val id: String,
    val from: TelegramUser,
    val message: TelegramMessage? = null,
    val data: String? = null
)

class TelegramBotHandler(
    private val config: AppConfig,
    private val fsmManager: FSMManager,
    private val profileService: ProfileService,
    private val workoutService: WorkoutService
) {
    private val logger = LoggerFactory.getLogger(TelegramBotHandler::class.java)
    private val scope = CoroutineScope(Dispatchers.Default)
    private val httpClient = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }
    private val telegramApiUrl = "https://api.telegram.org/bot${config.telegramBotToken}"
    
    suspend fun handleUpdate(update: TelegramUpdate) {
        try {
            when {
                update.message != null && update.message.text != null -> {
                    handleMessage(update.message)
                }
                update.message != null && update.message.voice != null -> {
                    handleVoiceMessage(update.message)
                }
                update.callback_query != null -> {
                    handleCallbackQuery(update.callback_query)
                }
                else -> {
                    logger.warn("Unsupported update type: ${update.update_id}")
                }
            }
        } catch (e: Exception) {
            logger.error("Error handling update", e)
        }
    }
    
    private suspend fun handleMessage(message: TelegramMessage) {
        val chatId = message.chat.id
        val text = message.text ?: return
        val userId = message.from.id
        
        when {
            text.startsWith("/") -> handleCommand(userId, chatId, text)
            else -> handleStateMessage(userId, chatId, text)
        }
    }
    
    private suspend fun handleCommand(userId: Long, chatId: Long, command: String) {
        val parts = command.split(" ", limit = 2)
        val cmd = parts[0].lowercase()
        val args = parts.getOrNull(1) ?: ""
        
        val responseText = when (cmd) {
            "/start" -> handleStartCommand(userId)
            "/help" -> handleHelpCommand()
            "/profile" -> handleProfileCommand(userId)
            "/workout" -> handleWorkoutCommand(userId)
            "/history" -> handleHistoryCommand(userId)
            else -> "Неизвестная команда. Используйте /help для списка команд."
        }
        
        sendMessage(chatId, responseText)
    }
    
    private suspend fun handleStartCommand(userId: Long): String {
        val profile = profileService.getProfile(userId)
        
        return if (profile == null) {
            fsmManager.transitionTo(userId, UserState.ONBOARDING_MEDICAL_CONFIRM)
            """
            Привет! Я бот для тренировок с гирями.
            
            Перед началом работы мне нужно убедиться, что у тебя нет медицинских противопоказаний к тренировкам.
            
            Подтверди, что у тебя нет медицинских противопоказаний к физическим нагрузкам.
            """.trimIndent()
        } else {
            """
            С возвращением! 
            
            Используй /workout для создания новой тренировки
            Используй /profile для просмотра и редактирования профиля
            Используй /history для просмотра истории тренировок
            """.trimIndent()
        }
    }
    
    private suspend fun handleHelpCommand(): String {
        return """
        Доступные команды:
        
        /start - Начать работу с ботом
        /help - Показать это сообщение
        /profile - Просмотр и редактирование профиля
        /workout - Создать новую тренировку
        /history - Просмотр истории тренировок
        """.trimIndent()
    }
    
    private suspend fun handleProfileCommand(userId: Long): String {
        val profile = profileService.getProfile(userId)
        
        return if (profile == null) {
            "Профиль не найден. Используйте /start для начала работы."
        } else {
            buildString {
                appendLine("📋 Твой профиль:")
                appendLine()
                appendLine("Опыт: ${profile.profile.experience.name}")
                appendLine("Вес тела: ${profile.profile.bodyWeight} кг")
                appendLine("Пол: ${profile.profile.gender.name}")
                appendLine("Доступные гири: ${profile.profile.weights.joinToString(", ")} кг")
                appendLine("Цель: ${profile.profile.goal}")
            }
        }
    }
    
    private suspend fun handleWorkoutCommand(userId: Long): String {
        val currentState = fsmManager.getCurrentState(userId)
        
        return if (currentState != UserState.IDLE) {
            "Сейчас ты находишься в процессе. Заверши текущее действие перед созданием новой тренировки."
        } else {
            try {
                fsmManager.transitionTo(userId, UserState.WORKOUT_REQUESTED)
                val workout = workoutService.generateWorkoutPlan(userId)
                
                buildString {
                    appendLine("💪 План тренировки:")
                    appendLine()
                    appendLine("Разминка:")
                    appendLine(workout.plan.warmup)
                    appendLine()
                    appendLine("Упражнения:")
                    workout.plan.exercises.forEachIndexed { index, ex ->
                        append("${index + 1}. ${ex.name} - ${ex.weight}кг")
                        if (ex.reps != null && ex.sets != null) {
                            append(" (${ex.reps}×${ex.sets})")
                        } else if (ex.timeWork != null && ex.timeRest != null) {
                            append(" (Работа: ${ex.timeWork}с, Отдых: ${ex.timeRest}с)")
                        }
                        appendLine()
                    }
                    appendLine()
                    appendLine("Заминка:")
                    appendLine(workout.plan.cooldown)
                }
            } catch (e: IllegalStateException) {
                fsmManager.transitionTo(userId, UserState.IDLE)
                "Ошибка: ${e.message}"
            } catch (e: Exception) {
                logger.error("Error generating workout", e)
                fsmManager.transitionTo(userId, UserState.IDLE)
                "Произошла ошибка при создании тренировки. Попробуйте позже."
            }
        }
    }
    
    private suspend fun handleHistoryCommand(userId: Long): String {
        val workouts = workoutService.getWorkoutHistory(userId, 10)
        
        return if (workouts.isEmpty()) {
            "У тебя пока нет завершенных тренировок."
        } else {
            buildString {
                appendLine("📊 История тренировок:")
                appendLine()
                workouts.forEachIndexed { index, workout ->
                    if (workout.status == com.kettlebell.model.WorkoutStatus.COMPLETED) {
                        appendLine("${index + 1}. ${workout.timing.completedAt?.let { java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy").format(it) } ?: "Дата неизвестна"}")
                        workout.actualPerformance?.let { perf ->
                            val volume = workoutService.calculateTotalVolume(workout)
                            appendLine("   Объем: ${volume} кг")
                            if (perf.rpe != null) {
                                appendLine("   RPE: ${perf.rpe}")
                            }
                        }
                        appendLine()
                    }
                }
            }
        }
    }
    
    private suspend fun handleStateMessage(userId: Long, chatId: Long, text: String) {
        val currentState = fsmManager.getCurrentState(userId)
        
        val responseText = when (currentState) {
            UserState.ONBOARDING_MEDICAL_CONFIRM -> {
                // TODO: Implement onboarding handlers (Task 11)
                "Обработка онбординга будет реализована в следующей задаче."
            }
            UserState.WORKOUT_FEEDBACK_PENDING -> {
                // TODO: Implement feedback handling (Task 12)
                "Обработка обратной связи будет реализована в следующей задаче."
            }
            else -> {
                "Используйте команды для взаимодействия с ботом. /help для списка команд."
            }
        }
        
        sendMessage(chatId, responseText)
    }
    
    private suspend fun handleVoiceMessage(message: TelegramMessage) {
        // TODO: Implement voice message handling (Task 12)
        sendMessage(message.chat.id, "Обработка голосовых сообщений будет реализована в следующей задаче.")
    }
    
    private suspend fun handleCallbackQuery(callbackQuery: TelegramCallbackQuery) {
        // TODO: Implement callback query handling (Task 12)
        val chatId = callbackQuery.message?.chat?.id ?: return
        sendMessage(chatId, "Обработка callback запросов будет реализована в следующей задаче.")
    }
    
    private suspend fun sendMessage(chatId: Long, text: String) {
        try {
            val response = httpClient.post("$telegramApiUrl/sendMessage") {
                contentType(ContentType.Application.Json)
                setBody(mapOf(
                    "chat_id" to chatId,
                    "text" to text
                ))
            }
            
            if (!response.status.isSuccess()) {
                logger.error("Failed to send message: ${response.status}")
            }
        } catch (e: Exception) {
            logger.error("Error sending message", e)
        }
    }
}
