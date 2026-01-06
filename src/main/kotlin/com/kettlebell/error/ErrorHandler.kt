package com.kettlebell.error

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

class ErrorHandler {
    private val logger = LoggerFactory.getLogger(ErrorHandler::class.java)
    
    /**
     * Maps AppError to user-friendly message in Russian
     */
    fun toUserMessage(error: AppError): String {
        return when (error) {
            is AppError.AIServiceUnavailable -> 
                "Сервис генерации тренировок временно недоступен. Попробуйте позже."
            
            is AppError.TranscriptionFailed -> 
                "Не удалось распознать голосовое сообщение. Попробуйте отправить текстом."
            
            is AppError.WorkoutGenerationFailed -> 
                "Не удалось создать план тренировки. Попробуйте позже."
            
            is AppError.FeedbackAnalysisFailed -> 
                "Не удалось проанализировать отзыв. Попробуйте еще раз."
            
            is AppError.DatabaseUnavailable -> 
                "База данных временно недоступна. Попробуйте позже."
            
            is AppError.DatabaseOperationFailed -> 
                "Ошибка при сохранении данных. Попробуйте еще раз."
            
            is AppError.ValidationError -> 
                error.message
            
            is AppError.InvalidInput -> 
                error.message
            
            is AppError.UserNotFound -> 
                "Пользователь не найден. Используйте /start для начала работы."
            
            is AppError.WorkoutNotFound -> 
                "Тренировка не найдена."
            
            is AppError.SubscriptionLimitExceeded -> 
                "Достигнут месячный лимит бесплатных тренировок. Обновите подписку для продолжения."
            
            is AppError.InvalidStateTransition -> 
                "Невозможно выполнить это действие в текущем состоянии."
            
            is AppError.UnexpectedError -> 
                "Произошла непредвиденная ошибка. Попробуйте позже или обратитесь в поддержку."
        }
    }
    
    /**
     * Executes a block with retry logic and exponential backoff
     * @param maxAttempts Maximum number of retry attempts (default: 3)
     * @param initialDelay Initial delay in milliseconds (default: 1000)
     * @param factor Exponential backoff factor (default: 2.0)
     * @param retryableErrors Set of error types that should be retried
     */
    suspend fun <T> withRetry(
        maxAttempts: Int = 3,
        initialDelay: Long = 1000,
        factor: Double = 2.0,
        retryableErrors: Set<Class<out AppError>> = setOf(
            AppError.DatabaseUnavailable::class.java,
            AppError.DatabaseOperationFailed::class.java,
            AppError.AIServiceUnavailable::class.java
        ),
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelay
        var lastException: Exception? = null
        
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: AppError) {
                lastException = e
                
                val shouldRetry = retryableErrors.any { it.isInstance(e) }
                
                if (!shouldRetry || attempt == maxAttempts - 1) {
                    logger.error("Error after ${attempt + 1} attempts: ${e.message}", e)
                    throw e
                }
                
                logger.warn("Attempt ${attempt + 1} failed: ${e.message}. Retrying in ${currentDelay}ms...", e)
                delay(currentDelay)
                currentDelay = (currentDelay * factor).toLong()
            } catch (e: Exception) {
                lastException = e
                
                // Convert unexpected exceptions to AppError
                val appError = when {
                    e.message?.contains("database", ignoreCase = true) == true ||
                    e.message?.contains("mongodb", ignoreCase = true) == true ||
                    e.message?.contains("connection", ignoreCase = true) == true ->
                        AppError.DatabaseUnavailable(e)
                    
                    e.message?.contains("openai", ignoreCase = true) == true ||
                    e.message?.contains("api", ignoreCase = true) == true ->
                        AppError.AIServiceUnavailable(e)
                    
                    else -> AppError.UnexpectedError(e)
                }
                
                val shouldRetry = retryableErrors.any { it.isInstance(appError) }
                
                if (!shouldRetry || attempt == maxAttempts - 1) {
                    logger.error("Unexpected error after ${attempt + 1} attempts: ${e.message}", e)
                    throw appError
                }
                
                logger.warn("Attempt ${attempt + 1} failed: ${e.message}. Retrying in ${currentDelay}ms...", e)
                delay(currentDelay)
                currentDelay = (currentDelay * factor).toLong()
            }
        }
        
        throw lastException ?: AppError.UnexpectedError()
    }
    
    /**
     * Wraps an exception into AppError
     */
    fun wrapException(e: Exception): AppError {
        return when (e) {
            is AppError -> e
            is IllegalArgumentException -> AppError.InvalidInput(e.message ?: "Invalid input")
            is IllegalStateException -> {
                when {
                    e.message?.contains("limit", ignoreCase = true) == true ->
                        AppError.SubscriptionLimitExceeded
                    e.message?.contains("not found", ignoreCase = true) == true ->
                        AppError.UserNotFound(-1) // Can't extract userId from message
                    else ->
                        AppError.ValidationError(e.message ?: "Invalid state")
                }
            }
            else -> {
                when {
                    e.message?.contains("database", ignoreCase = true) == true ||
                    e.message?.contains("mongodb", ignoreCase = true) == true ||
                    e.message?.contains("connection", ignoreCase = true) == true ->
                        AppError.DatabaseUnavailable(e)
                    
                    e.message?.contains("openai", ignoreCase = true) == true ||
                    e.message?.contains("api", ignoreCase = true) == true ->
                        AppError.AIServiceUnavailable(e)
                    
                    else -> AppError.UnexpectedError(e)
                }
            }
        }
    }
}

