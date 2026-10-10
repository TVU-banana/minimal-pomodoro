package com.akira.clock.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A daily habit with no timer or tag relationship. The archive day remains part of its history;
 * retaining this row and its check-ins keeps past days and backup merges reproducible.
 */
@Entity(tableName = "habits")
internal data class HabitEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Int,
    val createdLocalDateIso: String,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
    val archivedLocalDateIso: String? = null,
    @ColumnInfo(defaultValue = "''") val note: String = "",
)

/**
 * Repetitions are authoritative; completed is a compatibility cache maintained by local commands.
 * Date-effective targets determine completion after independent backup revisions are merged.
 * Undo retains a zero-count row with a newer revision so older backups cannot resurrect progress.
 */
@Entity(
    tableName = "habit_check_ins",
    primaryKeys = ["habitId", "localDateIso"],
    foreignKeys = [
        ForeignKey(
            entity = HabitEntity::class,
            parentColumns = ["id"],
            childColumns = ["habitId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("localDateIso")],
)
internal data class HabitCheckInEntity(
    val habitId: String,
    val localDateIso: String,
    val completed: Boolean,
    val completedAtUtcMs: Long?,
    val updatedAtUtcMs: Long,
    @ColumnInfo(defaultValue = "0") val completedCount: Int = if (completed) 1 else 0,
)

/** A target takes effect on its local day; missing history retains the legacy one-time goal. */
@Entity(
    tableName = "habit_targets",
    primaryKeys = ["habitId", "effectiveLocalDateIso"],
    foreignKeys = [
        ForeignKey(
            entity = HabitEntity::class,
            parentColumns = ["id"],
            childColumns = ["habitId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class HabitTargetEntity(
    val habitId: String,
    val effectiveLocalDateIso: String,
    val targetCount: Int,
    val updatedAtUtcMs: Long,
)
