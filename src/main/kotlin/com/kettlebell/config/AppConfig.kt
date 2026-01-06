package com.kettlebell.config

import io.ktor.server.application.*

data class AppConfig(
    val telegramBotToken: String,
    val openaiApiKey: String,
    val mongodbConnectionUri: String,
    val mongodbDatabaseName: String,
    val freeMonthlyLimit: Int
) {
    companion object {
        fun create(environment: ApplicationEnvironment): AppConfig {
            return AppConfig(
                telegramBotToken = environment.config.property("environment.telegram.bot.token").getString(),
                openaiApiKey = environment.config.property("environment.openai.api.key").getString(),
                mongodbConnectionUri = environment.config.property("environment.mongodb.connection.uri").getString(),
                mongodbDatabaseName = environment.config.property("environment.mongodb.database.name").getString(),
                freeMonthlyLimit = environment.config.property("environment.subscription.free.monthly.limit").getString().toInt()
            )
        }
    }
}

