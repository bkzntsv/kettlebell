package com.kettlebell.error

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

class ErrorHandlerTest : StringSpec({

    val errorHandler = ErrorHandler()

    "should map AI service unavailable error to user message" {
        val error = AppError.AIServiceUnavailable(Exception("API unavailable"))
        val message = errorHandler.toUserMessage(error)

        message shouldBe "Сервис генерации тренировок временно недоступен. Попробуйте позже."
    }

    "should map database unavailable error to user message" {
        val error = AppError.DatabaseUnavailable(Exception("Connection failed"))
        val message = errorHandler.toUserMessage(error)

        message shouldBe "База данных временно недоступна. Попробуйте позже."
    }

    "should map transcription failed error to user message" {
        val error = AppError.TranscriptionFailed(Exception("Transcription error"))
        val message = errorHandler.toUserMessage(error)

        message shouldBe "Не удалось распознать голосовое сообщение. Попробуйте отправить текстом."
    }

    "should map validation error to user message" {
        val error = AppError.ValidationError("Invalid input")
        val message = errorHandler.toUserMessage(error)

        message shouldBe "Invalid input"
    }

    "should map unexpected error to user message" {
        val error = AppError.UnexpectedError(Exception("Unknown error"))
        val message = errorHandler.toUserMessage(error)

        message shouldBe "Произошла непредвиденная ошибка. Попробуйте позже или обратитесь в поддержку."
    }

    "should retry database error up to max attempts" {
        var attempts = 0
        val databaseError = AppError.DatabaseUnavailable(Exception("Database error"))

        runBlocking {
            shouldThrow<AppError.DatabaseUnavailable> {
                errorHandler.withRetry(maxAttempts = 3) {
                    attempts++
                    throw databaseError
                }
            }
        }

        attempts shouldBe 3
    }

    "should not retry non-retryable errors" {
        var attempts = 0
        val validationError = AppError.ValidationError("Invalid input")

        runBlocking {
            shouldThrow<AppError.ValidationError> {
                errorHandler.withRetry(maxAttempts = 3) {
                    attempts++
                    throw validationError
                }
            }
        }

        attempts shouldBe 1 // Should not retry validation errors
    }

    "should wrap IllegalArgumentException to InvalidInput" {
        val exception = IllegalArgumentException("Invalid argument")
        val appError = errorHandler.wrapException(exception)

        appError shouldBe AppError.InvalidInput("Invalid argument")
    }

    "should wrap database-related exception to DatabaseUnavailable" {
        val exception = Exception("MongoDB connection failed")
        val appError = errorHandler.wrapException(exception)

        appError shouldBe AppError.DatabaseUnavailable(exception)
    }

    "should wrap OpenAI-related exception to AIServiceUnavailable" {
        val exception = Exception("OpenAI API error")
        val appError = errorHandler.wrapException(exception)

        appError shouldBe AppError.AIServiceUnavailable(exception)
    }

    "should wrap unknown exception to UnexpectedError" {
        val exception = Exception("Unknown error")
        val appError = errorHandler.wrapException(exception)

        appError shouldBe AppError.UnexpectedError(exception)
    }

    "should succeed after retry" {
        var attempts = 0

        runBlocking {
            val result =
                errorHandler.withRetry(maxAttempts = 3) {
                    attempts++
                    if (attempts < 2) {
                        throw AppError.DatabaseUnavailable(Exception("Temporary error"))
                    }
                    "Success"
                }

            result shouldBe "Success"
            attempts shouldBe 2
        }
    }
})
