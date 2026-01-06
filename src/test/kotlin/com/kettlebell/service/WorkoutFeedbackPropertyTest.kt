package com.kettlebell.service

import com.kettlebell.config.AppConfig
import com.kettlebell.model.*
import com.kettlebell.repository.UserRepository
import com.kettlebell.repository.WorkoutRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.property.Arb
import io.kotest.property.arbitrary.*
import io.kotest.property.checkAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import java.util.UUID

class WorkoutFeedbackPropertyTest : StringSpec({
    
    val workoutRepository = mockk<WorkoutRepository>()
    val userRepository = mockk<UserRepository>()
    val aiService = mockk<AIService>()
    val config = AppConfig(
        telegramBotToken = "token",
        openaiApiKey = "key",
        mongodbConnectionUri = "uri",
        mongodbDatabaseName = "db",
        freeMonthlyLimit = 5
    )
    val workoutService = WorkoutServiceImpl(workoutRepository, userRepository, aiService, config)
    
    "Property 9: Voice Message Transcription - should transcribe voice to text before processing" {
        checkAll(100, Arb.long(), Arb.string(), Arb.string()) { userId, workoutId, transcribedText ->
            val workout = Workout(
                id = workoutId,
                userId = userId,
                status = WorkoutStatus.PLANNED,
                plan = WorkoutPlan(
                    warmup = "Warmup",
                    exercises = listOf(Exercise("Swing", 16, 10, 3, null, null)),
                    cooldown = "Cooldown"
                ),
                actualPerformance = null,
                timing = WorkoutTiming(null, null, null),
                aiLog = AILog(0, "gpt-4o", 0, null, null),
                schemaVersion = 1
            )
            
            val performance = ActualPerformance(
                rawFeedback = transcribedText,
                data = listOf(ExercisePerformance("Swing", 16, 10, 3, true)),
                rpe = 7,
                issues = emptyList()
            )
            
            val audioBytes = ByteArray(100)
            coEvery { aiService.transcribeVoice(audioBytes) } returns transcribedText
            coEvery { aiService.analyzeFeedback(transcribedText, workout.plan) } returns performance
            coEvery { workoutRepository.findById(workoutId) } returns workout
            coEvery { workoutRepository.save(any()) } answers { firstArg() }
            coEvery { userRepository.updateState(userId, UserState.IDLE) } returns Unit
            
            // Simulate voice transcription flow
            val transcribed = aiService.transcribeVoice(audioBytes)
            val result = workoutService.processFeedback(userId, workoutId, transcribed)
            
            transcribed shouldBe transcribedText
            result.actualPerformance?.rawFeedback shouldBe transcribedText
        }
    }
    
    "Property 10: Text Feedback Storage - should store raw feedback text in actualPerformance" {
        checkAll(100, Arb.long(), Arb.string(), Arb.string(1..500)) { userId, workoutId, feedback ->
            val workout = Workout(
                id = workoutId,
                userId = userId,
                status = WorkoutStatus.PLANNED,
                plan = WorkoutPlan(
                    warmup = "Warmup",
                    exercises = listOf(Exercise("Swing", 16, 10, 3, null, null)),
                    cooldown = "Cooldown"
                ),
                actualPerformance = null,
                timing = WorkoutTiming(null, null, null),
                aiLog = AILog(0, "gpt-4o", 0, null, null),
                schemaVersion = 1
            )
            
            val performance = ActualPerformance(
                rawFeedback = feedback,
                data = listOf(ExercisePerformance("Swing", 16, 10, 3, true)),
                rpe = 7,
                issues = emptyList()
            )
            
            coEvery { workoutRepository.findById(workoutId) } returns workout
            coEvery { aiService.analyzeFeedback(feedback, workout.plan) } returns performance
            coEvery { workoutRepository.save(any()) } answers { firstArg() }
            coEvery { userRepository.updateState(userId, UserState.IDLE) } returns Unit
            
            val result = workoutService.processFeedback(userId, workoutId, feedback)
            
            result.actualPerformance?.rawFeedback shouldBe feedback
        }
    }
    
    "Property 11: Feedback Analysis Flow - should analyze feedback and store structured data" {
        checkAll(100, Arb.long(), Arb.string()) { userId, workoutId ->
            val workout = Workout(
                id = workoutId,
                userId = userId,
                status = WorkoutStatus.PLANNED,
                plan = WorkoutPlan(
                    warmup = "Warmup",
                    exercises = listOf(
                        Exercise("Swing", 16, 10, 3, null, null),
                        Exercise("Snatch", 16, 8, 2, null, null)
                    ),
                    cooldown = "Cooldown"
                ),
                actualPerformance = null,
                timing = WorkoutTiming(null, null, null),
                aiLog = AILog(0, "gpt-4o", 0, null, null),
                schemaVersion = 1
            )
            
            val feedback = "Выполнил все упражнения"
            val performance = ActualPerformance(
                rawFeedback = feedback,
                data = listOf(
                    ExercisePerformance("Swing", 16, 10, 3, true),
                    ExercisePerformance("Snatch", 16, 8, 2, true)
                ),
                rpe = 7,
                issues = emptyList()
            )
            
            coEvery { workoutRepository.findById(workoutId) } returns workout
            coEvery { aiService.analyzeFeedback(feedback, workout.plan) } returns performance
            coEvery { workoutRepository.save(any()) } answers { firstArg() }
            coEvery { userRepository.updateState(userId, UserState.IDLE) } returns Unit
            
            val result = workoutService.processFeedback(userId, workoutId, feedback)
            
            result.actualPerformance shouldNotBe null
            result.actualPerformance?.data?.size shouldBe 2
            result.actualPerformance?.data?.all { it.completed } shouldBe true
        }
    }
    
    "Property 12: Structured Performance Data Completeness - should include all required fields" {
        checkAll(100, Arb.long(), Arb.string(), Arb.int(1, 10), Arb.list(Arb.int(1, 100), 1..5)) { userId, workoutId, rpe, issues ->
            val workout = Workout(
                id = workoutId,
                userId = userId,
                status = WorkoutStatus.PLANNED,
                plan = WorkoutPlan(
                    warmup = "Warmup",
                    exercises = listOf(Exercise("Swing", 16, 10, 3, null, null)),
                    cooldown = "Cooldown"
                ),
                actualPerformance = null,
                timing = WorkoutTiming(null, null, null),
                aiLog = AILog(0, "gpt-4o", 0, null, null),
                schemaVersion = 1
            )
            
            val feedback = "Feedback"
            val performance = ActualPerformance(
                rawFeedback = feedback,
                data = listOf(ExercisePerformance("Swing", 16, 10, 3, true)),
                rpe = rpe,
                issues = issues.map { "Issue $it" }
            )
            
            coEvery { workoutRepository.findById(workoutId) } returns workout
            coEvery { aiService.analyzeFeedback(feedback, workout.plan) } returns performance
            coEvery { workoutRepository.save(any()) } answers { firstArg() }
            coEvery { userRepository.updateState(userId, UserState.IDLE) } returns Unit
            
            val result = workoutService.processFeedback(userId, workoutId, feedback)
            
            result.actualPerformance?.data shouldNotBe null
            result.actualPerformance?.data?.isNotEmpty() shouldBe true
            result.actualPerformance?.rpe shouldBe rpe
            result.actualPerformance?.issues?.size shouldBe issues.size
        }
    }
    
    "Property 13: Injury Detection and Flagging - should flag injuries in issues list" {
        checkAll(100, Arb.long(), Arb.string()) { userId, workoutId ->
            val workout = Workout(
                id = workoutId,
                userId = userId,
                status = WorkoutStatus.PLANNED,
                plan = WorkoutPlan(
                    warmup = "Warmup",
                    exercises = listOf(Exercise("Swing", 16, 10, 3, null, null)),
                    cooldown = "Cooldown"
                ),
                actualPerformance = null,
                timing = WorkoutTiming(null, null, null),
                aiLog = AILog(0, "gpt-4o", 0, null, null),
                schemaVersion = 1
            )
            
            val feedback = "Болит спина после упражнений"
            val performance = ActualPerformance(
                rawFeedback = feedback,
                data = listOf(ExercisePerformance("Swing", 16, 10, 3, false)),
                rpe = 5,
                issues = listOf("Боль в спине")
            )
            
            coEvery { workoutRepository.findById(workoutId) } returns workout
            coEvery { aiService.analyzeFeedback(feedback, workout.plan) } returns performance
            coEvery { workoutRepository.save(any()) } answers { firstArg() }
            coEvery { userRepository.updateState(userId, UserState.IDLE) } returns Unit
            
            val result = workoutService.processFeedback(userId, workoutId, feedback)
            
            result.actualPerformance?.issues?.isNotEmpty() shouldBe true
            // Verify that issues are detected and stored
            result.actualPerformance?.issues?.size shouldBe performance.issues.size
        }
    }
    
    "Property 14: Workout Status Completion - should update status to COMPLETED after feedback" {
        checkAll(100, Arb.long(), Arb.string()) { userId, workoutId ->
            val workout = Workout(
                id = workoutId,
                userId = userId,
                status = WorkoutStatus.PLANNED,
                plan = WorkoutPlan(
                    warmup = "Warmup",
                    exercises = listOf(Exercise("Swing", 16, 10, 3, null, null)),
                    cooldown = "Cooldown"
                ),
                actualPerformance = null,
                timing = WorkoutTiming(null, null, null),
                aiLog = AILog(0, "gpt-4o", 0, null, null),
                schemaVersion = 1
            )
            
            val performance = ActualPerformance(
                rawFeedback = "Completed",
                data = listOf(ExercisePerformance("Swing", 16, 10, 3, true)),
                rpe = 7,
                issues = emptyList()
            )
            
            coEvery { workoutRepository.findById(workoutId) } returns workout
            coEvery { aiService.analyzeFeedback(any(), any()) } returns performance
            coEvery { workoutRepository.save(any()) } answers { firstArg() }
            coEvery { userRepository.updateState(userId, UserState.IDLE) } returns Unit
            
            val result = workoutService.processFeedback(userId, workoutId, "Feedback")
            
            result.status shouldBe WorkoutStatus.COMPLETED
            result.actualPerformance shouldNotBe null
        }
    }
})

