package com.kettlebell.service

import com.kettlebell.model.AnalyticsEvent
import com.kettlebell.model.EventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.litote.kmongo.coroutine.CoroutineDatabase
import org.litote.kmongo.gte
import java.time.Instant
import java.time.temporal.ChronoUnit

class AnalyticsService(
    database: CoroutineDatabase,
) {
    private val collection = database.getCollection<AnalyticsEvent>("analytics_events")
    private val scope = CoroutineScope(Dispatchers.IO)

    fun track(
        userId: Long,
        type: EventType,
        name: String,
        metadata: Map<String, String> = emptyMap(),
    ) {
        scope.launch {
            try {
                collection.save(
                    AnalyticsEvent(
                        userId = userId,
                        type = type,
                        name = name,
                        metadata = metadata,
                    ),
                )
            } catch (e: Exception) {
                // Log error but don't crash app
                System.err.println("Failed to track event: ${e.message}")
            }
        }
    }

    suspend fun getDailyReport(): String {
        val now = Instant.now()
        val startOfDay = now.minus(24, ChronoUnit.HOURS)

        val eventsToday =
            collection.find(
                AnalyticsEvent::timestamp gte startOfDay,
            ).toList()

        val activeUsers = eventsToday.map { it.userId }.distinct().count()
        val newUsers = eventsToday.filter { it.name == "/start" && it.type == EventType.COMMAND }.count()
        val workoutsStarted = eventsToday.filter { it.name == "start_workout" }.count()
        val workoutsCompleted = eventsToday.filter { it.name == "finish_workout" }.count()

        val commands =
            eventsToday
                .filter { it.type == EventType.COMMAND }
                .groupBy { it.name }
                .mapValues { it.value.size }
                .entries
                .sortedByDescending { it.value }
                .take(5)

        return buildString {
            appendLine("📊 **Отчет за последние 24 часа:**")
            appendLine("👥 Активные пользователи (DAU): $activeUsers")
            appendLine("🆕 Новые пользователи: $newUsers")
            appendLine("🏋️‍♂️ Тренировки: начато $workoutsStarted / завершено $workoutsCompleted")
            appendLine()
            appendLine("🔝 Топ команд:")
            commands.forEach { (cmd, count) ->
                appendLine("- $cmd: $count")
            }
        }
    }
}
