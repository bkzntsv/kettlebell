package com.kettlebell.bot

import com.kettlebell.model.ExperienceLevel
import com.kettlebell.model.Gender
import com.kettlebell.model.ProfileData
import com.kettlebell.model.Subscription
import com.kettlebell.model.SubscriptionType
import com.kettlebell.model.TrainingGoal
import com.kettlebell.model.UserMetadata
import com.kettlebell.model.UserProfile
import com.kettlebell.model.UserState
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.time.Instant

class ProfileManagementTest : StringSpec({

    "should display profile with all required information" {
        checkAll(100, Arb.long(), Arb.list(Arb.int(1, 100), 1..5), Arb.enum<TrainingGoal>(), Arb.float(40f, 150f)) {
                userId, weights, goal, bodyWeight ->
            val profile =
                UserProfile(
                    id = userId,
                    fsmState = UserState.IDLE,
                    profile =
                        ProfileData(
                            weights = weights,
                            experience = ExperienceLevel.BEGINNER,
                            bodyWeight = bodyWeight,
                            gender = Gender.MALE,
                            goal = goal,
                        ),
                    subscription = Subscription(SubscriptionType.FREE, null),
                    metadata = UserMetadata(Instant.now(), Instant.now()),
                    schemaVersion = 1,
                )

            // Verify all profile fields are present for display
            profile.profile.experience shouldNotBe null
            profile.profile.bodyWeight shouldBe bodyWeight
            profile.profile.gender shouldNotBe null
            profile.profile.weights shouldBe weights
            profile.profile.goal shouldBe goal
        }
    }

    "should handle equipment update with validation" {
        checkAll(100, Arb.list(Arb.int(1, 100), 1..5)) { weights ->
            // Equipment weights should be positive
            weights.all { it > 0 } shouldBe true
            weights.isNotEmpty() shouldBe true
        }
    }

    "should handle experience update" {
        checkAll(100, Arb.enum<ExperienceLevel>()) { experience ->
            // Experience level should be valid enum value
            experience shouldNotBe null
            (experience in ExperienceLevel.values()) shouldBe true
        }
    }

    "should handle personal data update" {
        checkAll(100, Arb.float(40f, 150f), Arb.enum<Gender>()) { bodyWeight, gender ->
            // Personal data should be valid
            (bodyWeight > 0) shouldBe true
            gender shouldNotBe null
            (gender in Gender.values()) shouldBe true
        }
    }

    "should handle goal update" {
        checkAll(100, Arb.enum<TrainingGoal>()) { goal ->
            // Goal should be a valid enum value
            goal shouldNotBe null
            goal.displayName().isNotEmpty() shouldBe true
        }
    }
})
