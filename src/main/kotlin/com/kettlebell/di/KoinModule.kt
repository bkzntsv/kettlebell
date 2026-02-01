package com.kettlebell.di

import com.aallam.openai.api.http.Timeout
import com.aallam.openai.client.OpenAI
import com.kettlebell.bot.TelegramBotHandler
import com.kettlebell.config.AppConfig
import com.kettlebell.error.ErrorHandler
import com.kettlebell.repository.MongoUserRepository
import com.kettlebell.repository.MongoWorkoutRepository
import com.kettlebell.repository.UserRepository
import com.kettlebell.repository.WorkoutRepository
import com.kettlebell.service.AIService
import com.kettlebell.service.AIServiceImpl
import com.kettlebell.service.AnalyticsService
import com.kettlebell.service.FSMManager
import com.kettlebell.service.ProfileService
import com.kettlebell.service.ProfileServiceImpl
import com.kettlebell.service.WorkoutService
import com.kettlebell.service.WorkoutServiceImpl
import com.mongodb.reactivestreams.client.MongoClient
import io.ktor.server.application.ApplicationEnvironment
import org.koin.dsl.module
import org.litote.kmongo.coroutine.CoroutineDatabase
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.reactivestreams.KMongo
import kotlin.time.Duration.Companion.seconds

fun appModule(environment: ApplicationEnvironment) =
    module {
        single { AppConfig.create(environment) }

        single<MongoClient> {
            KMongo.createClient(get<AppConfig>().mongodbConnectionUri)
        }

        single<CoroutineDatabase> {
            get<MongoClient>().coroutine.getDatabase(get<AppConfig>().mongodbDatabaseName)
        }

        single<UserRepository> { MongoUserRepository(get()) }
        single<WorkoutRepository> { MongoWorkoutRepository(get()) }

        single<OpenAI> {
            OpenAI(
                token = get<AppConfig>().openaiApiKey,
                timeout = Timeout(socket = 120.seconds),
            )
        }

        single<AIService> { AIServiceImpl(get(), get()) }

        single { AnalyticsService(get()) }

        single { FSMManager(get(), get()) }

        single<ProfileService> { ProfileServiceImpl(get()) }

        single<WorkoutService> {
            WorkoutServiceImpl(
                workoutRepository = get(),
                userRepository = get(),
                aiService = get(),
                config = get(),
            )
        }

        single { ErrorHandler() }

        single {
            TelegramBotHandler(
                config = get(),
                fsmManager = get(),
                profileService = get(),
                workoutService = get(),
                aiService = get(),
                analyticsService = get(),
                errorHandler = get(),
            )
        }
    }
