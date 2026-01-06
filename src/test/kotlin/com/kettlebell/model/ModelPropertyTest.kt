package com.kettlebell.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.property.Arb
import io.kotest.property.arbitrary.*
import io.kotest.property.checkAll
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

class ModelPropertyTest : StringSpec({
    
    "Property 29: Schema Version Presence - UserProfile should always have schemaVersion" {
        checkAll(100, Arb.long(), Arb.string(), Arb.list(Arb.int(1, 100))) { userId, goal, weights ->
            val profile = UserProfile(
                id = userId,
                fsmState = UserState.IDLE,
                profile = ProfileData(
                    weights = weights,
                    experience = ExperienceLevel.BEGINNER,
                    bodyWeight = 70f,
                    gender = Gender.MALE,
                    goal = goal
                ),
                subscription = Subscription(SubscriptionType.FREE, null),
                metadata = UserMetadata(Instant.now(), Instant.now()),
                schemaVersion = 1
            )
            
            profile.schemaVersion shouldBe 1
        }
    }
    
    "Property 29: Schema Version Presence - Workout should always have schemaVersion" {
        checkAll(100, Arb.long(), Arb.string()) { userId, workoutId ->
            val workout = Workout(
                id = workoutId,
                userId = userId,
                status = WorkoutStatus.PLANNED,
                plan = WorkoutPlan(
                    warmup = "Warmup",
                    exercises = emptyList(),
                    cooldown = "Cooldown"
                ),
                actualPerformance = null,
                timing = WorkoutTiming(null, null, null),
                aiLog = AILog(0, "gpt-4o", 0, null, null),
                schemaVersion = 1
            )
            
            workout.schemaVersion shouldBe 1
        }
    }
})

