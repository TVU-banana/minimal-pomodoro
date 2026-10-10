package com.akira.clock.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** User-defined category tag with display color, default durations, and daily goal. */
@Entity(tableName = "categories")
internal data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorArgb: Long,
    val totalDurationMs: Long,
    val warmupDurationMs: Long,
    val dailyGoalMs: Long = 3 * 60 * 60_000L,
    val createdAtUtcMs: Long,
    val archivedAtUtcMs: Long? = null,
)

/**
 * Pre-configured time block preset specifying task name, primary category, and planned durations.
 */
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
internal data class TaskPresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val categoryId: String,
    // Presets store formal focus; warmup is added only when creating a new domain command.
    val totalDurationMs: Long,
    val warmupDurationMs: Long,
    val timerMode: String = "COUNTDOWN",
    val timerStyle: String = TimerStyle.DIGITAL.name,
    val backgroundAssetId: String? = null,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
)

/** A user-visible tag attached to a time block. The first tag remains the legacy primary tag. */
@Entity(
    tableName = "task_preset_tags",
    primaryKeys = ["presetId", "categoryId"],
    foreignKeys = [
        ForeignKey(
            entity = TaskPresetEntity::class,
            parentColumns = ["id"],
            childColumns = ["presetId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("categoryId")],
)
internal data class TaskPresetTagEntity(
    val presetId: String,
    val categoryId: String,
)

/**
 * Durable record of an executed timer session, capturing snapshot names and actual durations.
 */
@Entity(
    tableName = "sessions",
    indices = [Index("categoryId"), Index("startedAtUtcMs"), Index("status")],
)
internal data class SessionEntity(
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
    val abortReason: String? = null,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
)

/**
 * Granular timer activity segment (warmup or focus), cascading on session deletion.
 */
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
internal data class SessionSegmentEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val phase: String,
    val startedAtUtcMs: Long,
    val endedAtUtcMs: Long?,
)

/**
 * Singleton active timer state checkpoint used for process death and reboot recovery.
 */
@Entity(tableName = "timer_checkpoint")
internal data class TimerCheckpointEntity(
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

/**
 * Local media asset record tracked by SHA-256 digest and reference count.
 */
@Entity(tableName = "assets", indices = [Index(value = ["sha256"], unique = true)])
internal data class AssetEntity(
    @PrimaryKey val id: String,
    val sha256: String,
    val localPath: String,
    val width: Int,
    val height: Int,
    val referenceCount: Int,
    val createdAtUtcMs: Long,
)

/**
 * One user-authored reflection for a local calendar day. Blank content is represented by no row.
 */
@Entity(tableName = "daily_reflections")
internal data class DailyReflectionEntity(
    @PrimaryKey val localDateIso: String,
    val timezoneIdAtWrite: String,
    val text: String,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
)

internal enum class SessionStatus { RUNNING, PAUSED, COMPLETED, ABORTED }

internal enum class TimerStyle { DIGITAL, RING, HOURGLASS }

internal enum class SessionPhase { WARMUP, FOCUS }

internal data class CategoryReportRow(
    val categoryId: String,
    val categoryName: String,
    val warmupMs: Long,
    val focusMs: Long,
    val completedCount: Int,
    val abortedCount: Int,
) {
    val investedMs: Long get() = warmupMs + focusMs
}

internal data class DayFocusRow(
    val dayIndex: Int,
    val totalMs: Long,
)
