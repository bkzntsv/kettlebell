package com.kettlebell

import com.kettlebell.bot.TelegramBotHandler
import com.kettlebell.bot.TelegramUpdate
import com.kettlebell.config.AppConfig
import com.kettlebell.di.appModule
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import org.slf4j.LoggerFactory

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    install(Koin) {
        modules(appModule(environment))
    }

    val logger = LoggerFactory.getLogger(Application::class.java)
    val json = Json { ignoreUnknownKeys = true }

    // Inject dependencies using 'by inject()' to avoid conflict with routing 'get'
    val botHandler by inject<TelegramBotHandler>()
    val config by inject<AppConfig>()

    // Start reminder checker
    launch {
        while (true) {
            try {
                botHandler.checkReminders()
            } catch (e: Exception) {
                logger.error("Error in reminder loop", e)
            }
            kotlinx.coroutines.delay(60000) // Check every minute
        }
    }

    // Start polling if configured
    if (config.botMode == "polling") {
        launch {
            botHandler.startPolling()
        }
    }

    routing {
        post("/webhook/{token}") {
            try {
                val token = call.parameters["token"]

                if (token != config.telegramBotToken) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@post
                }

                val updateJson = call.receiveText()
                val update = json.decodeFromString<TelegramUpdate>(updateJson)

                launch {
                    try {
                        botHandler.handleUpdate(update)
                    } catch (e: Exception) {
                        logger.error("Error handling update", e)
                    }
                }

                call.respond(HttpStatusCode.OK)
            } catch (e: Exception) {
                logger.error("Error processing webhook", e)
                call.respond(HttpStatusCode.InternalServerError)
            }
        }

        get("/health") {
            call.respond(HttpStatusCode.OK, "OK")
        }
    }
}

