package com.kettlebell.model

import java.time.Instant
import java.util.UUID

data class AnalyticsEvent(
    val id: String = UUID.randomUUID().toString(),
    val userId: Long,
    val type: EventType,
    val name: String,
    val metadata: Map<String, String> = emptyMap(),
    val timestamp: Instant = Instant.now(),
)

enum class EventType {
    COMMAND,
    STATE_CHANGE,
    ACTION,
    ERROR,
}
