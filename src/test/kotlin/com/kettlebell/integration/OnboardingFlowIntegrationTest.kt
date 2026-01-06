package com.kettlebell.integration

import com.kettlebell.bot.TelegramBotHandler
import com.kettlebell.config.AppConfig
import com.kettlebell.model.*
import com.kettlebell.repository.MongoUserRepository
import com.kettlebell.repository.MongoWorkoutRepository
import com.kettlebell.repository.UserRepository
import com.kettlebell.repository.WorkoutRepository
import com.kettlebell.service.*
import com.kettlebell.error.ErrorHandler
import com.kettlebell.bot.TelegramUpdate
import com.kettlebell.bot.TelegramMessage
import com.kettlebell.bot.TelegramUser
import com.kettlebell.bot.TelegramChat
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.litote.kmongo.coroutine.CoroutineDatabase
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.reactivestreams.KMongo
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.utility.DockerImageName

class OnboardingFlowIntegrationTest : StringSpec({
    
    lateinit var mongoContainer: MongoDBContainer
    lateinit var userRepository: UserRepository
    lateinit var workoutRepository: WorkoutRepository
    lateinit var profileService: ProfileService
    lateinit var fsmManager: FSMManager
    lateinit var botHandler: TelegramBotHandler
    val userId = 12345L
    
    beforeSpec {
        mongoContainer = MongoDBContainer(DockerImageName.parse("mongo:7.0"))
        mongoContainer.start()
        
        val mongoClient = KMongo.createClient(mongoContainer.connectionString)
        val database: CoroutineDatabase = mongoClient.coroutine.getDatabase("test_db")
        
        userRepository = MongoUserRepository(database)
        workoutRepository = MongoWorkoutRepository(database)
        
        val aiService = mockk<AIService>()
        val config = AppConfig(
            telegramBotToken = "test_token",
            openaiApiKey = "test_key",
            mongodbConnectionUri = mongoContainer.connectionString,
            mongodbDatabaseName = "test_db",
            freeMonthlyLimit = 10
        )
        
        fsmManager = FSMManager(userRepository)
        profileService = ProfileServiceImpl(userRepository)
        val workoutService = WorkoutServiceImpl(workoutRepository, userRepository, aiService, config)
        val errorHandler = ErrorHandler()
        
        botHandler = TelegramBotHandler(
            config = config,
            fsmManager = fsmManager,
            profileService = profileService,
            workoutService = workoutService,
            aiService = aiService,
            errorHandler = errorHandler
        )
    }
    
    "Integration test: Complete onboarding flow end-to-end" {
        runBlocking {
            // Step 1: User sends /start command
            val startUpdate = TelegramUpdate(
                update_id = 1,
                message = TelegramMessage(
                    message_id = 1,
                    from = TelegramUser(id = userId, first_name = "Test", username = "testuser"),
                    chat = TelegramChat(id = userId, type = "private"),
                    text = "/start"
                )
            )
            
            botHandler.handleUpdate(startUpdate)
            
            // Verify: Profile initialized, state should be ONBOARDING_MEDICAL_CONFIRM
            val profileAfterStart = profileService.getProfile(userId)
            profileAfterStart shouldNotBe null
            profileAfterStart!!.fsmState shouldBe UserState.ONBOARDING_MEDICAL_CONFIRM
            
            // Step 2: User confirms medical clearance
            val medicalConfirmUpdate = TelegramUpdate(
                update_id = 2,
                message = TelegramMessage(
                    message_id = 2,
                    from = TelegramUser(id = userId, first_name = "Test", username = "testuser"),
                    chat = TelegramChat(id = userId, type = "private"),
                    text = "Да"
                )
            )
            botHandler.handleUpdate(medicalConfirmUpdate)
            
            // Step 3: User provides equipment information
            val equipmentUpdate = TelegramUpdate(
                update_id = 3,
                message = TelegramMessage(
                    message_id = 3,
                    from = TelegramUser(id = userId, first_name = "Test", username = "testuser"),
                    chat = TelegramChat(id = userId, type = "private"),
                    text = "16, 24"
                )
            )
            botHandler.handleUpdate(equipmentUpdate)
            
            // Verify: Equipment saved
            val profileAfterEquipment = profileService.getProfile(userId)
            profileAfterEquipment!!.profile.weights shouldBe listOf(16, 24)
            
            // Step 4: User selects experience level
            val experienceUpdate = TelegramUpdate(
                update_id = 4,
                message = TelegramMessage(
                    message_id = 4,
                    from = TelegramUser(id = userId, first_name = "Test", username = "testuser"),
                    chat = TelegramChat(id = userId, type = "private"),
                    text = "Любитель"
                )
            )
            botHandler.handleUpdate(experienceUpdate)
            
            // Verify: Experience saved
            val profileAfterExperience = profileService.getProfile(userId)
            profileAfterExperience!!.profile.experience shouldBe ExperienceLevel.AMATEUR
            
            // Step 5: User provides personal data
            val personalDataUpdate = TelegramUpdate(
                update_id = 5,
                message = TelegramMessage(
                    message_id = 5,
                    from = TelegramUser(id = userId, first_name = "Test", username = "testuser"),
                    chat = TelegramChat(id = userId, type = "private"),
                    text = "75.5 М"
                )
            )
            botHandler.handleUpdate(personalDataUpdate)
            
            // Verify: Personal data saved
            val profileAfterPersonal = profileService.getProfile(userId)
            profileAfterPersonal!!.profile.bodyWeight shouldBe 75.5f
            profileAfterPersonal.profile.gender shouldBe Gender.MALE
            
            // Step 6: User provides goal
            val goalUpdate = TelegramUpdate(
                update_id = 6,
                message = TelegramMessage(
                    message_id = 6,
                    from = TelegramUser(id = userId, first_name = "Test", username = "testuser"),
                    chat = TelegramChat(id = userId, type = "private"),
                    text = "Набрать мышечную массу и улучшить выносливость"
                )
            )
            botHandler.handleUpdate(goalUpdate)
            
            // Verify: Goal saved and state returns to IDLE
            val finalProfile = profileService.getProfile(userId)
            finalProfile!!.profile.goal shouldBe "Набрать мышечную массу и улучшить выносливость"
            finalProfile.fsmState shouldBe UserState.IDLE
            
            // Verify: Complete profile is saved correctly
            finalProfile.profile.weights shouldBe listOf(16, 24)
            finalProfile.profile.experience shouldBe ExperienceLevel.AMATEUR
            finalProfile.profile.bodyWeight shouldBe 75.5f
            finalProfile.profile.gender shouldBe Gender.MALE
            finalProfile.subscription.type shouldBe SubscriptionType.FREE
        }
    }
    
    afterSpec {
        mongoContainer.stop()
    }
})

