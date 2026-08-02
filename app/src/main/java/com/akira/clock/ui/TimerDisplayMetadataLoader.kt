package com.akira.clock.ui

import com.akira.clock.data.ClockDatabase

/** Presentation metadata stored outside the active timer checkpoint. */
internal data class TimerDisplayMetadata(
    val taskName: String,
    val categoryName: String,
)

/** Loads timer presentation metadata without exposing DAOs to the Fragment. */
internal class TimerDisplayMetadataLoader(
    private val database: ClockDatabase,
) {
    suspend fun load(sessionId: String): TimerDisplayMetadata? {
        val session = database.sessionDao().get(sessionId) ?: return null
        return TimerDisplayMetadata(
            taskName = session.taskNameSnapshot,
            categoryName = session.categoryNameSnapshot,
        )
    }
}
