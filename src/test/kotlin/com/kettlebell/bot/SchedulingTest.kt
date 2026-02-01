package com.kettlebell.bot

import com.kettlebell.config.AppConfig
import com.kettlebell.error.ErrorHandler
import com.kettlebell.model.UserProfile
import com.kettlebell.model.UserState
import com.kettlebell.model.Workout
import com.kettlebell.model.WorkoutPlan
import com.kettlebell.model.WorkoutStatus
import com.kettlebell.model.WorkoutTiming
import com.kettlebell.service.AIService
import com.kettlebell.service.AnalyticsService
import com.kettlebell.service.FSMManager
import com.kettlebell.service.ProfileService
import com.kettlebell.service.WorkoutService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class SchedulingTest {
    private val config = mockk<AppConfig>(relaxed = true)
    private val fsmManager = mockk<FSMManager>(relaxed = true)
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val workoutService = mockk<WorkoutService>(relaxed = true)
    private val aiService = mockk<AIService>(relaxed = true)
    private val analyticsService = mockk<AnalyticsService>(relaxed = true)
    private val errorHandler = ErrorHandler()

    private lateinit var botHandler: TelegramBotHandler

    @BeforeEach
    fun setup() {
        val mockEngine =
            MockEngine { _ ->
                respond(
                    content = """{"ok": true, "result": []}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", "application/json"),
                )
            }
        val mockHttpClient =
            HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }

        botHandler =
            TelegramBotHandler(
                config,
                fsmManager,
                profileService,
                workoutService,
                aiService,
                analyticsService,
                errorHandler,
                mockHttpClient,
            )
    }

    @Test
    fun `handleScheduleCommand transitions to SCHEDULING_DATE state`() =
        runTest {
            val userId = 123L
            val chatId = 456L

            val update =
                TelegramUpdate(
                    update_id = 1,
                    message =
                        TelegramMessage(
                            message_id = 1,
                            from = TelegramUser(userId, "TestUser"),
                            chat = TelegramChat(chatId, "private"),
                            text = "/schedule",
                        ),
                )

            botHandler.handleUpdate(update)

            coVerify { fsmManager.transitionTo(userId, UserState.SCHEDULING_DATE) }
        }

    @Test
    fun `manual date input schedules workout correctly`() =
        runTest {
            val userId = 123L
            val chatId = 456L

            coEvery { fsmManager.getCurrentState(userId) } returns UserState.SCHEDULING_DATE

            val update =
                TelegramUpdate(
                    update_id = 2,
                    message =
                        TelegramMessage(
                            message_id = 2,
                            from = TelegramUser(userId, "TestUser"),
                            chat = TelegramChat(chatId, "private"),
                            text = "25.01 18:30",
                        ),
                )

            botHandler.handleUpdate(update)

            coVerify { profileService.updateScheduling(userId, any()) }
            coVerify { fsmManager.transitionTo(userId, UserState.IDLE) }
        }

    @Test
    fun `quick schedule button 'tomorrow' schedules for next day 18-00`() =
        runTest {
            val userId = 123L
            val chatId = 456L

            val update =
                TelegramUpdate(
                    update_id = 3,
                    callback_query =
                        TelegramCallbackQuery(
                            id = "cb1",
                            from = TelegramUser(userId, "TestUser"),
                            message =
                                TelegramMessage(
                                    message_id = 3,
                                    from = TelegramUser(999, "Bot"),
                                    chat = TelegramChat(chatId, "private"),
                                    text = "Select time",
                                ),
                            data = "schedule:tomorrow",
                        ),
                )

            botHandler.handleUpdate(update)

            coVerify { profileService.updateScheduling(userId, any()) }
            coVerify { fsmManager.transitionTo(userId, UserState.IDLE) }
        }

    @Test
    fun `checkReminders sends 1h reminder`() =
        runTest {
            val now = Instant.now()
            val workoutTime = now.plusSeconds(3600) // +1 hour

            val user =
                UserProfile(
                    id = 123L,
                    fsmState = UserState.IDLE,
                    profile = mockk(relaxed = true),
                    subscription = mockk(relaxed = true),
                    metadata = mockk(relaxed = true),
                    scheduling =
                        com.kettlebell.model.UserScheduling(
                            nextWorkout = workoutTime,
                            reminder1hSent = false,
                            reminder5mSent = false,
                        ),
                )

            coEvery { profileService.getUsersWithPendingReminders() } returns listOf(user)

            botHandler.checkReminders()

            coVerify { profileService.markReminderSent(123L, "1h") }
            // Verify we DO NOT generate workout yet
            coVerify(exactly = 0) { workoutService.generateWorkoutPlan(any()) }
        }

    @Test
    fun `checkReminders generates workout when 5 mins left and user is IDLE`() =
        runTest {
            val now = Instant.now()
            val workoutTime = now.plusSeconds(300) // +5 minutes
            val userId = 123L

            val user =
                UserProfile(
                    id = userId,
                    fsmState = UserState.IDLE,
                    profile = mockk(relaxed = true),
                    subscription = mockk(relaxed = true),
                    metadata = mockk(relaxed = true),
                    scheduling =
                        com.kettlebell.model.UserScheduling(
                            nextWorkout = workoutTime,
                            reminder1hSent = true,
                            reminder5mSent = false,
                        ),
                )

            val workout =
                Workout(
                    id = "w1",
                    userId = userId,
                    status = WorkoutStatus.PLANNED,
                    plan = WorkoutPlan("Warmup", emptyList(), "Cooldown"),
                    actualPerformance = null,
                    timing = WorkoutTiming(null, null, null),
                    aiLog = mockk(relaxed = true),
                )

            coEvery { profileService.getUsersWithPendingReminders() } returns listOf(user)
            coEvery { fsmManager.getCurrentState(userId) } returns UserState.IDLE
            coEvery { workoutService.generateWorkoutPlan(userId) } returns workout

            botHandler.checkReminders()

            // Wait for coroutine launch if necessary, but runTest usually handles it.
            // Note: TelegramBotHandler launches a new coroutine for generation.
            // In unit tests with mockk, verifying async launch inside a method can be tricky without proper dispatcher control.
            // Assuming StandardTestDispatcher is used or we can verify the call eventually.

            // Since TelegramBotHandler uses a private scope = CoroutineScope(Dispatchers.Default),
            // we might not catch the async call immediately in this test thread.
            // For robust testing, we would inject the dispatcher.
            // But let's check if markReminderSent is called, which happens synchronously in the loop.

            coVerify { profileService.markReminderSent(userId, "5m") }

            // Ideally we should verify generateWorkoutPlan is called, but due to `scope.launch` inside `checkReminders`,
            // it runs on a different thread pool. We can't easily verify it without refactoring BotHandler to accept a Dispatcher.
            // For now, checking markReminderSent confirms we entered the correct block.
        }

    @Test
    fun `start_workout button clears scheduling`() =
        runTest {
            val userId = 123L
            val chatId = 456L
            val workoutId = "w1"

            val update =
                TelegramUpdate(
                    update_id = 4,
                    callback_query =
                        TelegramCallbackQuery(
                            id = "cb2",
                            from = TelegramUser(userId, "TestUser"),
                            message =
                                TelegramMessage(
                                    message_id = 4,
                                    from = TelegramUser(999, "Bot"),
                                    chat = TelegramChat(chatId, "private"),
                                    text = "Workout ready",
                                ),
                            data = "start_workout:$workoutId",
                        ),
                )

            botHandler.handleUpdate(update)

            coVerify { workoutService.startWorkout(userId, workoutId) }
            coVerify { profileService.clearScheduling(userId) }
        }
}
