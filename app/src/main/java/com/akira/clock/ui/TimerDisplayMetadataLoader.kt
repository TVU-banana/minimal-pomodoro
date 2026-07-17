package com.akira.clock.ui

import com.akira.clock.data.ClockDatabase
import com.akira.clock.data.TimerStyle
import java.io.File

/** Presentation metadata stored outside the active timer checkpoint. */
internal data class TimerDisplayMetadata(
    val taskName: String,
    val categoryName: String,
    val style: TimerStyle,
    val backgroundAssetId: String?,
    val backgroundFile: File?,
)

/** Loads timer presentation metadata without exposing DAOs to the Fragment. */
internal class TimerDisplayMetadataLoader(
    private val database: ClockDatabase,
    private val filesDirectory: File,
) {
    suspend fun load(sessionId: String): TimerDisplayMetadata? {
        val session = database.sessionDao().get(sessionId) ?: return null
        val preset = session.presetId?.let { database.taskPresetDao().get(it) }
        val asset = preset?.backgroundAssetId?.let { database.assetDao().get(it) }
        return TimerDisplayMetadata(
            taskName = session.taskNameSnapshot,
            categoryName = session.categoryNameSnapshot,
            style = preset?.timerStyle
                ?.let { runCatching { TimerStyle.valueOf(it) }.getOrNull() }
                ?: TimerStyle.DIGITAL,
            backgroundAssetId = asset?.id,
            backgroundFile = asset?.let { File(filesDirectory, it.localPath) },
        )
    }
}
