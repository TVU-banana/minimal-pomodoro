package com.akira.clock.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorArgb: Long,
    val createdAtUtcMs: Long,
    val archivedAtUtcMs: Long? = null,
)

@Entity(
    tableName = "task_presets",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("categoryId")],
)
data class TaskPresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val categoryId: String,
    val totalDurationMs: Long,
    val warmupDurationMs: Long,
    val timerStyle: String = TimerStyle.DIGITAL.name,
    val backgroundAssetId: String? = null,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
)

@Entity(
    tableName = "sessions",
    indices = [Index("categoryId"), Index("startedAtUtcMs"), Index("status")],
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val presetId: String?,
    val categoryId: String,
    val categoryNameSnapshot: String,
    val taskNameSnapshot: String = "",
    val plannedTotalMs: Long,
    val plannedWarmupMs: Long,
    val actualWarmupMs: Long,
    val actualFocusMs: Long,
    val startedAtUtcMs: Long,
    val endedAtUtcMs: Long?,
    val timezoneId: String,
    val status: String,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
)

@Entity(
    tableName = "session_segments",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index("startedAtUtcMs")],
)
data class SessionSegmentEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val phase: String,
    val startedAtUtcMs: Long,
    val endedAtUtcMs: Long?,
)

@Entity(tableName = "timer_checkpoint")
data class TimerCheckpointEntity(
    @PrimaryKey val singletonId: Int = SINGLETON_ID,
    val sessionId: String,
    val stage: String,
    val previousStage: String?,
    val totalDurationMs: Long,
    val warmupDurationMs: Long,
    val phaseStartedElapsedMs: Long?,
    val deadlineElapsedMs: Long?,
    val deadlineUtcMs: Long?,
    val accumulatedWarmupMs: Long,
    val accumulatedFocusMs: Long,
    val bootCount: Int,
    val updatedAtUtcMs: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Entity(tableName = "assets", indices = [Index(value = ["sha256"], unique = true)])
data class AssetEntity(
    @PrimaryKey val id: String,
    val sha256: String,
    val localPath: String,
    val width: Int,
    val height: Int,
    val referenceCount: Int,
    val createdAtUtcMs: Long,
)

enum class SessionStatus { RUNNING, PAUSED, COMPLETED, ABORTED }

enum class TimerStyle { DIGITAL, RING, HOURGLASS }

enum class SessionPhase { WARMUP, FOCUS }

data class CategoryReportRow(
    val categoryId: String,
    val categoryName: String,
    val warmupMs: Long,
    val focusMs: Long,
    val completedCount: Int,
    val abortedCount: Int,
) {
    val investedMs: Long get() = warmupMs + focusMs
}
