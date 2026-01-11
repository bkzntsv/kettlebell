package com.kettlebell.integration

import com.kettlebell.bot.TelegramBotHandler
import com.kettlebell.bot.TelegramCallbackQuery
import com.kettlebell.bot.TelegramChat
import com.kettlebell.bot.TelegramMessage
import com.kettlebell.bot.TelegramUpdate
import com.kettlebell.bot.TelegramUser
import com.kettlebell.config.AppConfig
import com.kettlebell.error.ErrorHandler
import com.kettlebell.model.ActualPerformance
import com.kettlebell.model.Exercise
import com.kettlebell.model.ExercisePerformance
import com.kettlebell.model.ExperienceLevel
import com.kettlebell.model.Gender
import com.kettlebell.model.ProfileData
import com.kettlebell.model.UserState
import com.kettlebell.model.WorkoutPlan
import com.kettlebell.model.WorkoutStatus
import com.kettlebell.repository.MongoUserRepository
import com.kettlebell.repository.MongoWorkoutRepository
import com.kettlebell.repository.UserRepository
import com.kettlebell.repository.WorkoutRepository
import com.kettlebell.service.AIService
import com.kettlebell.service.FSMManager
import com.kettlebell.service.ProfileService
import com.kettlebell.service.ProfileServiceImpl
import com.kettlebell.service.WorkoutService
import com.kettlebell.service.WorkoutServiceImpl
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.litote.kmongo.coroutine.CoroutineDatabase
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.reactivestreams.KMongo
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.utility.DockerImageName

class WorkoutFlowIntegrationTest : StringSpec({

    lateinit var mongoContainer: MongoDBContainer
    lateinit var userRepository: UserRepository
    lateinit var workoutRepository: WorkoutRepository
    lateinit var profileService: ProfileService
    lateinit var workoutService: WorkoutService
    lateinit var fsmManager: FSMManager
    lateinit var botHandler: TelegramBotHandler
    lateinit var aiService: AIService
    val userId = 67890L

    beforeSpec {
        mongoContainer = MongoDBContainer(DockerImageName.parse("mongo:7.0"))
        mongoContainer.start()

        val mongoClient = KMongo.createClient(mongoContainer.connectionString)
        val database: CoroutineDatabase = mongoClient.coroutine.getDatabase("test_db")

        userRepository = MongoUserRepository(database)
        workoutRepository = MongoWorkoutRepository(database)

        aiService = mockk<AIService>()
        val config =
            AppConfig(
                telegramBotToken = "test_token",
                openaiApiKey = "test_key",
                mongodbConnectionUri = mongoContainer.connectionString,
                mongodbDatabaseName = "test_db",
                freeMonthlyLimit = 10,
            )

        fsmManager = FSMManager(userRepository)
        profileService = ProfileServiceImpl(userRepository)
        workoutService = WorkoutServiceImpl(workoutRepository, userRepository, aiService, config)
        val errorHandler = ErrorHandler()
        val mockEngine = MockEngine { _ ->
            respond(
                content = """{"ok": true, "result": []}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json")
            )
        }
        val mockHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        botHandler =
            TelegramBotHandler(
                config = config,
                fsmManager = fsmManager,
                profileService = profileService,
                workoutService = workoutService,
                aiService = aiService,
                errorHandler = errorHandler,
                httpClient = mockHttpClient
            )
    }

    "Integration test: Complete workout flow end-to-end" {
        runBlocking {
            // Setup: Create user profile with equipment
            val profileData =
                ProfileData(
                    weights = listOf(16, 24),
                    experience = ExperienceLevel.AMATEUR,
                    bodyWeight = 75.0f,
                    gender = Gender.MALE,
                    goal = "Улучшить силу",
                )
            profileService.createProfile(userId, profileData)
            fsmManager.transitionTo(userId, UserState.IDLE)

            // Mock AI service responses
            val mockWorkoutPlan =
                WorkoutPlan(
                    warmup = "5 минут разминки",
                    exercises =
                        listOf(
                            Exercise("Swing", 16, 10, 3, null, 60),
                            Exercise("Goblet Squat", 16, 8, 3, null, 90),
                        ),
                    cooldown = "5 минут заминки",
                )

            coEvery { aiService.generateWorkoutPlan(any()) } returns mockWorkoutPlan

            val mockPerformance =
                ActualPerformance(
                    rawFeedback = "Выполнил все упражнения, чувствовал себя хорошо",
                    data =
                        listOf(
                            ExercisePerformance("Swing", 16, 10, 3, true),
                            ExercisePerformance("Goblet Squat", 16, 8, 3, true),
                        ),
                    rpe = 7,
                    issues = emptyList(),
                )

            coEvery { aiService.analyzeFeedback(any(), any()) } returns mockPerformance

            // Step 1: User requests workout
            val workoutRequestUpdate =
                TelegramUpdate(
                    update_id = 1,
                    message =
                        TelegramMessage(
                            message_id = 1,
                            from = TelegramUser(id = userId, first_name = "Test", username = "testuser"),
                            chat = TelegramChat(id = userId, type = "private"),
                            text = "/workout",
                        ),
                )

            botHandler.handleUpdate(workoutRequestUpdate)

            // Verify: Workout created and state changed
            val stateAfterRequest = fsmManager.getCurrentState(userId)
            stateAfterRequest shouldBe UserState.WORKOUT_REQUESTED

            val workouts = workoutRepository.findByUserId(userId, 10)
            workouts.size shouldBe 1
            val workout = workouts.first()
            workout.status shouldBe WorkoutStatus.PLANNED
            workout.plan shouldBe mockWorkoutPlan

            // Step 2: User starts workout (simulate button click)
            val startWorkoutCallback =
                TelegramUpdate(
                    update_id = 2,
                    callback_query =
                        TelegramCallbackQuery(
                            id = "callback1",
                            from = TelegramUser(id = userId, first_name = "Test", username = "testuser"),
                            message =
                                TelegramMessage(
                                    message_id = 1,
                                    from = TelegramUser(id = userId, first_name = "Test", username = "testuser"),
                                    chat = TelegramChat(id = userId, type = "private"),
                                    text = null,
                                ),
                            data = "start_workout:${workout.id}",
                        ),
                )

            botHandler.handleUpdate(startWorkoutCallback)

            // Verify: Workout started
            val startedWorkout = workoutRepository.findById(workout.id)
            startedWorkout shouldNotBe null
            startedWorkout!!.status shouldBe WorkoutStatus.IN_PROGRESS
            startedWorkout.timing.startedAt shouldNotBe null

            val stateAfterStart = fsmManager.getCurrentState(userId)
            stateAfterStart shouldBe UserState.WORKOUT_IN_PROGRESS

            // Step 3: User finishes workout (simulate button click)
            val finishWorkoutCallback =
                TelegramUpdate(
                    update_id = 3,
                    callback_query =
                        TelegramCallbackQuery(
                            id = "callback2",
                            from = TelegramUser(id = userId, first_name = "Test", username = "testuser"),
                            message =
                                TelegramMessage(
                                    message_id = 2,
                                    from = TelegramUser(id = userId, first_name = "Test", username = "testuser"),
                                    chat = TelegramChat(id = userId, type = "private"),
                                    text = null,
                                ),
                            data = "finish_workout:${workout.id}",
                        ),
                )

            botHandler.handleUpdate(finishWorkoutCallback)

            // Verify: Workout finished, waiting for feedback
            val finishedWorkout = workoutRepository.findById(workout.id)
            finishedWorkout shouldNotBe null
            finishedWorkout!!.status shouldBe WorkoutStatus.IN_PROGRESS // Still IN_PROGRESS until feedback
            finishedWorkout.timing.completedAt shouldNotBe null
            finishedWorkout.timing.durationSeconds shouldNotBe null

            val stateAfterFinish = fsmManager.getCurrentState(userId)
            stateAfterFinish shouldBe UserState.WORKOUT_FEEDBACK_PENDING

            // Step 4: User provides text feedback
            val feedbackUpdate =
                TelegramUpdate(
                    update_id = 4,
                    message =
                        TelegramMessage(
                            message_id = 4,
                            from = TelegramUser(id = userId, first_name = "Test", username = "testuser"),
                            chat = TelegramChat(id = userId, type = "private"),
                            text = "Выполнил все упражнения, чувствовал себя хорошо",
                        ),
                )

            botHandler.handleUpdate(feedbackUpdate)

            // Verify: Feedback processed, workout completed
            val completedWorkout = workoutRepository.findById(workout.id)
            completedWorkout shouldNotBe null
            completedWorkout!!.status shouldBe WorkoutStatus.COMPLETED
            val actualPerformance = completedWorkout.actualPerformance
            actualPerformance shouldNotBe null
            actualPerformance!!.rawFeedback shouldBe "Выполнил все упражнения, чувствовал себя хорошо"
            actualPerformance.data.size shouldBe 2
            actualPerformance.rpe shouldBe 7

            val stateAfterFeedback = fsmManager.getCurrentState(userId)
            stateAfterFeedback shouldBe UserState.IDLE

            // Verify: Total volume calculated correctly
            val totalVolume = workoutService.calculateTotalVolume(completedWorkout)
            totalVolume shouldBe (16 * 10 * 3) + (16 * 8 * 3) // 480 + 384 = 864

            // Verify: Workout appears in history
            val history = workoutService.getWorkoutHistory(userId, 10)
            history.size shouldBe 1
            history.first().id shouldBe workout.id
        }
    }

    afterSpec {
        mongoContainer.stop()
    }
})
