package com.kettlebell

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.http.*
import org.koin.ktor.plugin.Koin
import org.koin.ktor.ext.inject
import com.kettlebell.di.appModule
import com.kettlebell.bot.TelegramBotHandler
import com.kettlebell.config.AppConfig
import com.kettlebell.bot.TelegramUpdate
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlinx.serialization.json.Json

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
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

