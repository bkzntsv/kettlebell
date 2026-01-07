package com.kettlebell.bot

import com.kettlebell.model.ExperienceLevel
import com.kettlebell.model.Gender
import com.kettlebell.config.AppConfig
import com.kettlebell.model.UserState
import com.kettlebell.service.FSMManager
import com.kettlebell.service.ProfileService
import com.kettlebell.service.WorkoutService
import com.kettlebell.service.AIService
import com.kettlebell.error.ErrorHandler
import com.kettlebell.error.AppError
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.serialization.kotlinx.json.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.slf4j.LoggerFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
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

@Serializable
data class InlineKeyboardMarkup(
    val inline_keyboard: List<List<InlineKeyboardButton>>
)

@Serializable
data class InlineKeyboardButton(
    val text: String,
    val callback_data: String
)

@Serializable
data class GetFileResponse(
    val result: TelegramFile
)

@Serializable
data class TelegramFile(
    val file_id: String,
    val file_path: String? = null
)

@Serializable
data class SendMessageRequest(
    val chat_id: Long,
    val text: String,
    val reply_markup: InlineKeyboardMarkup? = null
)

@Serializable
data class GetUpdatesResponse(
    val ok: Boolean,
    val result: List<TelegramUpdate>
)

class TelegramBotHandler(
    private val config: AppConfig,
    private val fsmManager: FSMManager,
    private val profileService: ProfileService,
    private val workoutService: WorkoutService,
    private val aiService: AIService,
    private val errorHandler: ErrorHandler
) {
    private val logger = LoggerFactory.getLogger(TelegramBotHandler::class.java)
    private val scope = CoroutineScope(Dispatchers.Default)
    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { 
                ignoreUnknownKeys = true 
                encodeDefaults = false 
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60000 // 60 seconds for long polling
            connectTimeoutMillis = 10000
            socketTimeoutMillis = 60000
        }
    }
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = false 
    }
    private val telegramApiUrl = "https://api.telegram.org/bot${config.telegramBotToken}"
    private val telegramFileUrl = "https://api.telegram.org/file/bot${config.telegramBotToken}"
    
    suspend fun startPolling() {
        logger.info("Starting Telegram Bot in POLLING mode...")
        var offset = 0L
        
        while (scope.isActive) {
            try {
                val response = httpClient.get("$telegramApiUrl/getUpdates") {
                    parameter("offset", offset)
                    parameter("timeout", 30) // Long polling timeout
                }
                
                if (response.status == HttpStatusCode.OK) {
                    val updatesResponse = response.body<GetUpdatesResponse>()
                    
                    if (updatesResponse.ok) {
                        for (update in updatesResponse.result) {
                            handleUpdate(update)
                            offset = update.update_id + 1
                        }
                    }
                } else {
                    logger.error("Failed to get updates: ${response.status}")
                    delay(5000)
                }
            } catch (e: Exception) {
                logger.error("Error in polling loop", e)
                delay(5000)
            }
        }
    }
    
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
            val appError = errorHandler.wrapException(e)
            // Try to send error message if we have chatId
            update.message?.chat?.id?.let { chatId ->
                try {
                    sendMessage(chatId, errorHandler.toUserMessage(appError))
                } catch (sendError: Exception) {
                    logger.error("Failed to send error message", sendError)
                }
            }
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
        // val args = parts.getOrNull(1) ?: ""
        
        when (cmd) {
            "/start" -> handleStartCommand(userId, chatId)
            "/help" -> handleHelpCommand(chatId)
            "/profile" -> handleProfileCommand(userId, chatId)
            "/workout" -> handleWorkoutCommand(userId, chatId)
            "/history" -> handleHistoryCommand(userId, chatId)
            "/reset" -> handleResetCommand(userId, chatId)
            else -> sendMessage(chatId, "Неизвестная команда. Используйте /help для списка команд.")
        }
    }
    
    private suspend fun handleResetCommand(userId: Long, chatId: Long) {
        profileService.initProfile(userId)
        fsmManager.transitionTo(userId, UserState.ONBOARDING_MEDICAL_CONFIRM)
        sendMessage(chatId, """
            Профиль полностью сброшен. Начинаем заново.
            
            Привет! Я бот для тренировок с гирями.
            
            Перед началом работы мне нужно убедиться, что у тебя нет медицинских противопоказаний к тренировкам.
            
            Подтверди, что у тебя нет медицинских противопоказаний к физическим нагрузкам.
            Напиши "Да" или "Подтверждаю".
        """.trimIndent())
    }
    
    private suspend fun handleStartCommand(userId: Long, chatId: Long) {
        val profile = profileService.getProfile(userId)
        
        if (profile == null) {
            profileService.initProfile(userId)
            fsmManager.transitionTo(userId, UserState.ONBOARDING_MEDICAL_CONFIRM)
            sendMessage(chatId, """
            Привет! Я бот для тренировок с гирями.
            
            Перед началом работы мне нужно убедиться, что у тебя нет медицинских противопоказаний к тренировкам.
            
            Подтверди, что у тебя нет медицинских противопоказаний к физическим нагрузкам.
            Напиши "Да" или "Подтверждаю".
            """.trimIndent())
        } else {
            if (profile.fsmState != UserState.IDLE && profile.fsmState.name.startsWith("ONBOARDING")) {
                 sendMessage(chatId, resumeOnboarding(profile.fsmState))
            } else {
                sendMessage(chatId, """
                С возвращением! 
                
                Используй /workout для создания новой тренировки
                Используй /profile для просмотра и редактирования профиля
                Используй /history для просмотра истории тренировок
                """.trimIndent())
            }
        }
    }
    
    private suspend fun resumeOnboarding(state: UserState): String {
        return when (state) {
            UserState.ONBOARDING_MEDICAL_CONFIRM -> "Подтверди отсутствие медицинских противопоказаний (напиши 'Да')."
            UserState.ONBOARDING_EQUIPMENT -> "Какие у тебя есть гири? Напиши вес в кг через запятую (например: 16, 24)."
            UserState.ONBOARDING_EXPERIENCE -> "Какой у тебя опыт тренировок? (Новичок, Любитель, Про)."
            UserState.ONBOARDING_PERSONAL_DATA -> "Напиши свой вес (кг) и пол (М/Ж). Например: 80 М"
            UserState.ONBOARDING_GOALS -> "Какая у тебя цель тренировок? (например: Сила, Выносливость, Похудение)"
            else -> "Продолжаем..."
        }
    }
    
    private suspend fun handleHelpCommand(chatId: Long) {
        sendMessage(chatId, """
        Доступные команды:
        
        /start - Начать работу с ботом
        /help - Показать это сообщение
        /profile - Просмотр и редактирование профиля
        /workout - Создать новую тренировку
        /history - Просмотр истории тренировок
        """.trimIndent())
    }
    
    private suspend fun handleProfileCommand(userId: Long, chatId: Long) {
        val profile = profileService.getProfile(userId)
        
        if (profile == null) {
            sendMessage(chatId, "Профиль не найден. Используйте /start для начала работы.")
        } else {
            val text = buildString {
                appendLine("📋 Твой профиль:")
                appendLine()
                appendLine("Опыт: ${profile.profile.experience.name}")
                appendLine("Вес тела: ${profile.profile.bodyWeight} кг")
                appendLine("Пол: ${profile.profile.gender.name}")
                appendLine("Доступные гири: ${profile.profile.weights.joinToString(", ")} кг")
                appendLine("Цель: ${profile.profile.goal}")
            }
            
            val keyboard = InlineKeyboardMarkup(listOf(
                listOf(InlineKeyboardButton("Изменить гири", "edit_equipment")),
                listOf(InlineKeyboardButton("Изменить опыт", "edit_experience")),
                listOf(InlineKeyboardButton("Изменить вес/пол", "edit_personal_data")),
                listOf(InlineKeyboardButton("Изменить цель", "edit_goal"))
            ))
            
            sendMessage(chatId, text, keyboard)
        }
    }
    
    private suspend fun handleWorkoutCommand(userId: Long, chatId: Long) {
        val currentState = fsmManager.getCurrentState(userId)
        
        if (currentState != UserState.IDLE) {
            val keyboard = InlineKeyboardMarkup(listOf(
                listOf(InlineKeyboardButton("Отменить текущее действие", "cancel_action"))
            ))
            sendMessage(chatId, "Сейчас ты находишься в процессе. Заверши текущее действие перед созданием новой тренировки.", keyboard)
        } else {
            try {
                sendMessage(chatId, "Генерирую тренировку... Подождите немного.")
                fsmManager.transitionTo(userId, UserState.WORKOUT_REQUESTED)
                
                val workout = errorHandler.withRetry {
                    workoutService.generateWorkoutPlan(userId)
                }
                
                val text = buildString {
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
                
                val keyboard = InlineKeyboardMarkup(listOf(
                    listOf(InlineKeyboardButton("Начать тренировку", "start_workout:${workout.id}"))
                ))
                
                sendMessage(chatId, text, keyboard)
            } catch (e: AppError) {
                fsmManager.transitionTo(userId, UserState.IDLE)
                sendMessage(chatId, errorHandler.toUserMessage(e))
            } catch (e: Exception) {
                fsmManager.transitionTo(userId, UserState.IDLE)
                val appError = errorHandler.wrapException(e)
                sendMessage(chatId, errorHandler.toUserMessage(appError))
            }
        }
    }
    
    private suspend fun handleHistoryCommand(userId: Long, chatId: Long) {
        val workouts = workoutService.getWorkoutHistory(userId, 10)
        
        if (workouts.isEmpty()) {
            sendMessage(chatId, "У тебя пока нет завершенных тренировок.")
        } else {
            val text = buildString {
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
            sendMessage(chatId, text)
        }
    }
    
    private suspend fun handleStateMessage(userId: Long, chatId: Long, text: String) {
        val currentState = fsmManager.getCurrentState(userId)
        
        when (currentState) {
            UserState.ONBOARDING_MEDICAL_CONFIRM -> sendMessage(chatId, handleOnboardingMedical(userId, text))
            UserState.ONBOARDING_EQUIPMENT -> sendMessage(chatId, handleOnboardingEquipment(userId, text))
            UserState.ONBOARDING_EXPERIENCE -> sendMessage(chatId, handleOnboardingExperience(userId, text))
            UserState.ONBOARDING_PERSONAL_DATA -> sendMessage(chatId, handleOnboardingPersonalData(userId, text))
            UserState.ONBOARDING_GOALS -> sendMessage(chatId, handleOnboardingGoals(userId, text))
            UserState.EDIT_EQUIPMENT -> sendMessage(chatId, handleEditEquipment(userId, text))
            UserState.EDIT_EXPERIENCE -> sendMessage(chatId, handleEditExperience(userId, text))
            UserState.EDIT_PERSONAL_DATA -> sendMessage(chatId, handleEditPersonalData(userId, text))
            UserState.EDIT_GOAL -> sendMessage(chatId, handleEditGoal(userId, text))
            UserState.WORKOUT_FEEDBACK_PENDING -> {
                // Find latest workout pending feedback
                val workouts = workoutService.getWorkoutHistory(userId, 1)
                // We need to find workout that is either IN_PROGRESS (if just finished) or COMPLETED (if re-processing?)
                // Actually, finishWorkout should have been called before entering this state.
                // But workout status in DB? 
                // Let's assume the last updated workout is the one.
                val workout = workouts.firstOrNull() 
                
                if (workout != null) {
                    processFeedback(userId, chatId, workout.id, text)
                } else {
                    fsmManager.transitionTo(userId, UserState.IDLE)
                    sendMessage(chatId, "Не найдена активная тренировка для отзыва.")
                }
            }
            else -> {
                sendMessage(chatId, "Используйте команды для взаимодействия с ботом. /help для списка команд.")
            }
        }
    }
    
    private suspend fun processFeedback(userId: Long, chatId: Long, workoutId: String, feedback: String) {
        try {
            sendMessage(chatId, "Анализирую ваш отзыв...")
            
            val workout = errorHandler.withRetry {
                workoutService.processFeedback(userId, workoutId, feedback)
            }
            
            val volume = workoutService.calculateTotalVolume(workout)
            val performance = workout.actualPerformance
            
            // Log what we have for debugging
            logger.info("Performance data: recoveryStatus=${performance?.recoveryStatus}, technicalNotes=${performance?.technicalNotes?.take(50)}, issues=${performance?.issues}, coachFeedback=${performance?.coachFeedback?.take(50)}")
            
            val warning = if (volume == 0) {
                "\n\n⚠️ Внимание: общий объем равен 0. Возможно, я не смог распознать упражнения в твоем отзыве. Проверь историю и при необходимости напиши мне снова."
            } else {
                ""
            }
            
            val message = buildString {
                appendLine("Тренировка завершена! 🎉")
                appendLine()
                appendLine("Общий объем: $volume кг")
                appendLine("RPE: ${performance?.rpe ?: "-"}")
                
                // Add recovery status if available
                performance?.recoveryStatus?.takeIf { it.isNotBlank() }?.let { status ->
                    appendLine("Статус восстановления: $status")
                }
                
                // Add technical notes if available
                performance?.technicalNotes?.takeIf { it.isNotBlank() }?.let { notes ->
                    appendLine()
                    appendLine("📝 Технические заметки:")
                    appendLine(notes)
                }
                
                // Add issues/red flags if any
                performance?.issues?.takeIf { it.isNotEmpty() }?.let { issues ->
                    appendLine()
                    appendLine("⚠️ Обрати внимание:")
                    issues.forEach { issue ->
                        appendLine("• $issue")
                    }
                }
                
                // Add coach feedback if available
                performance?.coachFeedback?.takeIf { it.isNotBlank() }?.let { feedback ->
                    appendLine()
                    appendLine("💬 От тренера:")
                    appendLine(feedback)
                }
                
                append(warning)
                appendLine()
                appendLine("Отдыхай!")
            }
            
            logger.info("Sending message to user: ${message.take(200)}")
            sendMessage(chatId, message.trim())
        } catch (e: AppError) {
            sendMessage(chatId, errorHandler.toUserMessage(e))
        } catch (e: Exception) {
            val appError = errorHandler.wrapException(e)
            sendMessage(chatId, errorHandler.toUserMessage(appError))
        }
    }

    private suspend fun handleOnboardingMedical(userId: Long, text: String): String {
        val positiveAnswers = listOf("да", "yes", "подтверждаю", "confirm", "ок", "ok", "+")
        if (text.lowercase().trim() in positiveAnswers) {
            fsmManager.transitionTo(userId, UserState.ONBOARDING_EQUIPMENT)
            return "Отлично! Теперь расскажи, какие у тебя есть гири. Напиши их веса в кг через запятую (например: 16, 24)."
        }
        return "Пожалуйста, подтверди отсутствие медицинских противопоказаний, написав 'Да' или 'Подтверждаю'."
    }

    private suspend fun handleOnboardingEquipment(userId: Long, text: String): String {
        val weights = text.split(",", " ", ";")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it > 0 }
            .distinct()
            .sorted()
        
        if (weights.isEmpty()) {
            return "Не удалось распознать веса. Пожалуйста, введи положительные числа через запятую (например: 16, 24)."
        }
        
        try {
            profileService.updateEquipment(userId, weights)
            fsmManager.transitionTo(userId, UserState.ONBOARDING_EXPERIENCE)
            return "Принято: ${weights.joinToString(", ")} кг.\n\nТеперь укажи свой опыт тренировок с гирями:\n- Новичок (Beginner)\n- Любитель (Amateur)\n- Профи (Pro)"
        } catch (e: Exception) {
            logger.error("Error updating equipment", e)
            return "Произошла ошибка при сохранении данных. Попробуй еще раз."
        }
    }

    private suspend fun handleOnboardingExperience(userId: Long, text: String): String {
        val input = text.lowercase().trim()
        val experience = when {
            "новичок" in input || "beginner" in input -> ExperienceLevel.BEGINNER
            "любитель" in input || "amateur" in input -> ExperienceLevel.AMATEUR
            "про" in input || "pro" in input -> ExperienceLevel.PRO
            else -> null
        }
        
        if (experience == null) {
            return "Пожалуйста, выбери один из вариантов: Новичок, Любитель, Профи."
        }
        
        try {
            profileService.updateExperience(userId, experience)
            fsmManager.transitionTo(userId, UserState.ONBOARDING_PERSONAL_DATA)
            return "Опыт: ${experience.name}.\n\nТеперь напиши свой вес (в кг) и пол (М/Ж).\nНапример: 80 М"
        } catch (e: Exception) {
            logger.error("Error updating experience", e)
            return "Произошла ошибка. Попробуй еще раз."
        }
    }

    private suspend fun handleOnboardingPersonalData(userId: Long, text: String): String {
        // Simple regex to find a number (weight) and a letter (gender)
        val parts = text.split(" ", ",", ";").map { it.trim() }.filter { it.isNotEmpty() }
        
        var bodyWeight: Float? = null
        var gender: Gender? = null
        
        for (part in parts) {
            if (bodyWeight == null) {
                val weight = part.replace(",", ".").toFloatOrNull()
                if (weight != null && weight > 0) {
                    bodyWeight = weight
                    continue
                }
            }
            
            if (gender == null) {
                val g = part.lowercase()
                if (g.startsWith("м") || g.startsWith("m")) gender = Gender.MALE
                else if (g.startsWith("ж") || g.startsWith("f") || g.startsWith("w")) gender = Gender.FEMALE
            }
        }
        
        if (bodyWeight == null) {
            return "Не удалось распознать вес. Пожалуйста, укажи вес числом (например: 80)."
        }
        
        // Default gender if not parsed
        val finalGender = gender ?: Gender.MALE 
        
        try {
            profileService.updatePersonalData(userId, bodyWeight, finalGender)
            fsmManager.transitionTo(userId, UserState.ONBOARDING_GOALS)
            return "Вес: $bodyWeight кг, Пол: ${finalGender.name}.\n\nПоследний шаг: какая у тебя основная цель тренировок?\n(например: Сила, Выносливость, Похудение, ОФП)"
        } catch (e: Exception) {
            logger.error("Error updating personal data", e)
            return "Произошла ошибка. Попробуй еще раз."
        }
    }

    private suspend fun handleOnboardingGoals(userId: Long, text: String): String {
        if (text.isBlank()) {
            return "Пожалуйста, напиши свою цель."
        }
        
        try {
            profileService.updateGoal(userId, text.trim())
            fsmManager.transitionTo(userId, UserState.IDLE)
            return """
            Отлично! Твой профиль создан.
            
            Цель: $text
            
            Теперь ты можешь создать свою первую тренировку командой /workout.
            """.trimIndent()
        } catch (e: Exception) {
            logger.error("Error updating goal", e)
            return "Произошла ошибка. Попробуй еще раз."
        }
    }
    
    private suspend fun handleEditEquipment(userId: Long, text: String): String {
        val weights = text.split(",", " ", ";")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it > 0 }
            .distinct()
            .sorted()
        
        if (weights.isEmpty()) {
            return "Не удалось распознать веса. Пожалуйста, введи положительные числа через запятую (например: 16, 24)."
        }
        
        return try {
            errorHandler.withRetry {
                profileService.updateEquipment(userId, weights)
            }
            fsmManager.transitionTo(userId, UserState.IDLE)
            "Гири обновлены: ${weights.joinToString(", ")} кг.\n\nИзменения применятся к будущим тренировкам."
        } catch (e: AppError) {
            errorHandler.toUserMessage(e)
        } catch (e: Exception) {
            val appError = errorHandler.wrapException(e)
            errorHandler.toUserMessage(appError)
        }
    }

    private suspend fun handleEditExperience(userId: Long, text: String): String {
        val input = text.lowercase().trim()
        val experience = when {
            "новичок" in input || "beginner" in input -> ExperienceLevel.BEGINNER
            "любитель" in input || "amateur" in input -> ExperienceLevel.AMATEUR
            "про" in input || "pro" in input -> ExperienceLevel.PRO
            else -> null
        }
        
        if (experience == null) {
            return "Пожалуйста, выбери один из вариантов: Новичок, Любитель, Профи."
        }
        
        return try {
            errorHandler.withRetry {
                profileService.updateExperience(userId, experience)
            }
            fsmManager.transitionTo(userId, UserState.IDLE)
            "Опыт обновлен: ${experience.name}.\n\nИзменения применятся к будущим тренировкам."
        } catch (e: AppError) {
            errorHandler.toUserMessage(e)
        } catch (e: Exception) {
            val appError = errorHandler.wrapException(e)
            errorHandler.toUserMessage(appError)
        }
    }

    private suspend fun handleEditPersonalData(userId: Long, text: String): String {
        val parts = text.split(" ", ",", ";").map { it.trim() }.filter { it.isNotEmpty() }
        
        var bodyWeight: Float? = null
        var gender: Gender? = null
        
        for (part in parts) {
            if (bodyWeight == null) {
                val weight = part.replace(",", ".").toFloatOrNull()
                if (weight != null && weight > 0) {
                    bodyWeight = weight
                    continue
                }
            }
            
            if (gender == null) {
                val g = part.lowercase()
                if (g.startsWith("м") || g.startsWith("m")) gender = Gender.MALE
                else if (g.startsWith("ж") || g.startsWith("f") || g.startsWith("w")) gender = Gender.FEMALE
            }
        }
        
        if (bodyWeight == null) {
            return "Не удалось распознать вес. Пожалуйста, укажи вес числом (например: 80)."
        }
        
        val finalGender = gender ?: Gender.MALE 
        
        return try {
            errorHandler.withRetry {
                profileService.updatePersonalData(userId, bodyWeight, finalGender)
            }
            fsmManager.transitionTo(userId, UserState.IDLE)
            "Данные обновлены: вес $bodyWeight кг, пол ${finalGender.name}.\n\nИзменения применятся к будущим тренировкам."
        } catch (e: AppError) {
            errorHandler.toUserMessage(e)
        } catch (e: Exception) {
            val appError = errorHandler.wrapException(e)
            errorHandler.toUserMessage(appError)
        }
    }

    private suspend fun handleEditGoal(userId: Long, text: String): String {
        if (text.isBlank()) {
            return "Пожалуйста, напиши свою цель."
        }
        
        return try {
            errorHandler.withRetry {
                profileService.updateGoal(userId, text.trim())
            }
            fsmManager.transitionTo(userId, UserState.IDLE)
            "Цель обновлена: $text\n\nИзменения применятся к будущим тренировкам."
        } catch (e: AppError) {
            errorHandler.toUserMessage(e)
        } catch (e: Exception) {
            val appError = errorHandler.wrapException(e)
            errorHandler.toUserMessage(appError)
        }
    }
    
    private suspend fun handleVoiceMessage(message: TelegramMessage) {
        val userId = message.from.id
        val chatId = message.chat.id
        val currentState = fsmManager.getCurrentState(userId)
        
        if (currentState == UserState.WORKOUT_FEEDBACK_PENDING) {
            val fileId = message.voice?.file_id ?: return
            
            try {
                // 1. Get file path
                val fileResponse = httpClient.get("$telegramApiUrl/getFile?file_id=$fileId")
                val fileInfo = fileResponse.body<GetFileResponse>()
                val filePath = fileInfo.result.file_path ?: return
                
                // 2. Download file
                val fileBytes = httpClient.get("$telegramFileUrl/$filePath").body<ByteArray>()
                
                // 3. Transcribe with retry
                sendMessage(chatId, "Обрабатываю голосовое сообщение...")
                val text = errorHandler.withRetry {
                    aiService.transcribeVoice(fileBytes)
                }
                
                // 4. Process feedback
                val workouts = workoutService.getWorkoutHistory(userId, 1)
                val workout = workouts.firstOrNull() // Latest
                
                if (workout != null) {
                    processFeedback(userId, chatId, workout.id, text)
                } else {
                    fsmManager.transitionTo(userId, UserState.IDLE)
                    sendMessage(chatId, "Не найдена активная тренировка для отзыва.")
                }
                
            } catch (e: AppError) {
                sendMessage(chatId, errorHandler.toUserMessage(e))
            } catch (e: Exception) {
                val appError = errorHandler.wrapException(e)
                sendMessage(chatId, errorHandler.toUserMessage(appError))
            }
        } else {
            sendMessage(chatId, "Голосовые сообщения принимаются только для отзыва о тренировке.")
        }
    }
    
    private suspend fun handleCallbackQuery(callbackQuery: TelegramCallbackQuery) {
        val chatId = callbackQuery.message?.chat?.id ?: return
        val userId = callbackQuery.from.id
        val data = callbackQuery.data ?: return
        
        val parts = data.split(":", limit = 2)
        val action = parts[0]
        val workoutId = parts.getOrNull(1)
        
        try {
            when (action) {
                "start_workout" -> {
                    if (workoutId == null) return
                    workoutService.startWorkout(userId, workoutId)
                    val keyboard = InlineKeyboardMarkup(listOf(
                        listOf(InlineKeyboardButton("Завершить тренировку", "finish_workout:$workoutId"))
                    ))
                    sendMessage(chatId, "Тренировка начата! Удачи! 💪\nНажми кнопку ниже, когда закончишь.", keyboard)
                }
                "finish_workout" -> {
                    if (workoutId == null) return
                    workoutService.finishWorkout(userId, workoutId)
                    sendMessage(chatId, "Тренировка завершена. Как все прошло? Расскажи о весах, повторениях и ощущениях (текстом или голосом).")
                }
                "edit_equipment" -> {
                    fsmManager.transitionTo(userId, UserState.EDIT_EQUIPMENT)
                    sendMessage(chatId, "Напиши новые веса гирь в кг через запятую (например: 16, 24).")
                }
                "edit_experience" -> {
                    fsmManager.transitionTo(userId, UserState.EDIT_EXPERIENCE)
                    sendMessage(chatId, "Укажи свой опыт тренировок с гирями:\n- Новичок (Beginner)\n- Любитель (Amateur)\n- Профи (Pro)")
                }
                "edit_personal_data" -> {
                    fsmManager.transitionTo(userId, UserState.EDIT_PERSONAL_DATA)
                    sendMessage(chatId, "Напиши свой вес (кг) и пол (М/Ж). Например: 80 М")
                }
                "edit_goal" -> {
                    fsmManager.transitionTo(userId, UserState.EDIT_GOAL)
                    sendMessage(chatId, "Напиши свою новую цель тренировок.")
                }
                "cancel_action" -> {
                    fsmManager.transitionTo(userId, UserState.IDLE)
                    sendMessage(chatId, "Действие отменено. Теперь ты можешь начать новую тренировку с /workout.")
                }
            }
        } catch (e: Exception) {
            logger.error("Error handling callback: $action", e)
            val appError = errorHandler.wrapException(e)
            sendMessage(chatId, errorHandler.toUserMessage(appError))
        }
    }
    
    private suspend fun sendMessage(chatId: Long, text: String, replyMarkup: InlineKeyboardMarkup? = null) {
        try {
            val request = SendMessageRequest(chatId, text, replyMarkup)
            
            val response = httpClient.post("$telegramApiUrl/sendMessage") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(serializer<SendMessageRequest>(), request))
            }
            
            if (!response.status.isSuccess()) {
                logger.error("Failed to send message: ${response.status} ${response.bodyAsText()}")
            }
        } catch (e: Exception) {
            logger.error("Error sending message", e)
        }
    }
}