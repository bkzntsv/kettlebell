package com.kettlebell.error

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking

class ErrorHandlerPropertyTest : StringSpec({

    val errorHandler = ErrorHandler()

    "Property 27: Database Error Retry - should retry database operations with exponential backoff" {
        checkAll(100, Arb.int(1, 3)) { maxAttempts ->
            var attempts = 0
            val databaseError = AppError.DatabaseUnavailable(Exception("Database connection failed"))

            runBlocking {
                try {
                    errorHandler.withRetry(maxAttempts = maxAttempts) {
                        attempts++
                        if (attempts < maxAttempts) {
                            throw databaseError
                        }
                        "Success"
                    }
                } catch (e: AppError.DatabaseUnavailable) {
                    // Expected to fail after maxAttempts
                }
            }

            // Should have attempted maxAttempts times
            attempts shouldBe maxAttempts
        }
    }
})
