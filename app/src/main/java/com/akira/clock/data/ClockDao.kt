package com.akira.clock.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Data access operations for category tags.
 *
 * Categories represent user-defined task tags with color, custom duration defaults, and
 * daily goals. Soft-deleted categories retain archived timestamps to preserve historical records.
 */
@Dao
internal interface CategoryDao {
    @Upsert
    suspend fun upsert(category: CategoryEntity)

    @Upsert
    suspend fun upsertAll(categories: List<CategoryEntity>)

    @Query("SELECT * FROM categories WHERE archivedAtUtcMs IS NULL ORDER BY name COLLATE NOCASE")
    fun observeActive(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY id")
    suspend fun allForExport(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun get(id: String): CategoryEntity?

    @Query("UPDATE categories SET archivedAtUtcMs = :archivedAtUtcMs WHERE id = :id")
    suspend fun archive(id: String, archivedAtUtcMs: Long)
}

/**
 * Data access operations for configured time block presets.
 *
 * Presets pre-populate the timer with planned duration and tag associations. Presets link
 * to categories through foreign keys and cascade on preset deletion.
 */
@Dao
internal interface TaskPresetDao {
    @Query(
        """
        SELECT task_presets.* FROM task_presets
        JOIN categories ON categories.id = task_presets.categoryId
        WHERE categories.archivedAtUtcMs IS NULL AND task_presets.timerMode = 'COUNTDOWN'
        ORDER BY task_presets.id LIMIT 5 OFFSET :offset
        """,
    )
    suspend fun bandPage(offset: Int): List<TaskPresetEntity>

    @Upsert
    suspend fun upsert(preset: TaskPresetEntity)

    @Upsert
    suspend fun upsertAll(presets: List<TaskPresetEntity>)

    @Query("SELECT * FROM task_presets ORDER BY updatedAtUtcMs DESC")
    fun observeAll(): Flow<List<TaskPresetEntity>>

    @Query("SELECT * FROM task_presets ORDER BY id")
    suspend fun allForExport(): List<TaskPresetEntity>

    @Query("SELECT * FROM task_presets WHERE id = :id LIMIT 1")
    suspend fun get(id: String): TaskPresetEntity?

    @Query("SELECT * FROM task_presets WHERE categoryId = :categoryId ORDER BY createdAtUtcMs")
    fun observeByCategory(categoryId: String): Flow<List<TaskPresetEntity>>

    @Query("SELECT * FROM task_presets WHERE categoryId = :categoryId ORDER BY createdAtUtcMs")
    suspend fun listByCategory(categoryId: String): List<TaskPresetEntity>

    @Query("DELETE FROM task_presets WHERE id = :id")
    suspend fun delete(id: String)
}

/**
 * Junction table operations linking task presets to additional categories (tags).
 *
 * Maintains the many-to-many relationship allowing presets to associate with multiple tags.
 */
@Dao
internal interface TaskPresetTagDao {
    @Upsert
    suspend fun upsertAll(tags: List<TaskPresetTagEntity>)

    @Query("DELETE FROM task_preset_tags WHERE presetId = :presetId")
    suspend fun deleteForPreset(presetId: String)

    @Query("SELECT * FROM task_preset_tags WHERE presetId = :presetId ORDER BY categoryId")
    suspend fun listForPreset(presetId: String): List<TaskPresetTagEntity>

    @Query("SELECT * FROM task_preset_tags")
    fun observeAll(): Flow<List<TaskPresetTagEntity>>
}

/**
 * Data access operations for completed, aborted, and running timer sessions.
 *
 * Serves as the primary source of truth for focus history and daily progress. Queries
 * filtering by time bounds leverage the SQLite index on [startedAtUtcMs].
 */
@Dao
internal interface SessionDao {
    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Upsert
    suspend fun upsertAll(sessions: List<SessionEntity>)

    @Query("SELECT * FROM sessions ORDER BY startedAtUtcMs DESC, id DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query(
        """
        SELECT * FROM sessions
        WHERE startedAtUtcMs >= :fromUtcMs AND startedAtUtcMs < :toUtcMs
        ORDER BY startedAtUtcMs, id
        """,
    )
    fun observeInRange(fromUtcMs: Long, toUtcMs: Long): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY startedAtUtcMs, id")
    suspend fun allForExport(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun get(id: String): SessionEntity?

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query(
        """
        SELECT sessions.categoryId AS categoryId,
               categories.name AS categoryName,
               COALESCE(SUM(actualWarmupMs), 0) AS warmupMs,
               COALESCE(SUM(actualFocusMs), 0) AS focusMs,
               SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) AS completedCount,
               SUM(CASE WHEN status = 'ABORTED' THEN 1 ELSE 0 END) AS abortedCount
        FROM sessions
        JOIN categories ON categories.id = sessions.categoryId
        WHERE startedAtUtcMs >= :fromUtcMs
          AND startedAtUtcMs < :toUtcMs
          AND status IN ('COMPLETED', 'ABORTED')
        GROUP BY sessions.categoryId, categories.name
        ORDER BY focusMs DESC
        """,
    )
    suspend fun report(fromUtcMs: Long, toUtcMs: Long): List<CategoryReportRow>

    @Query(
        """
        SELECT COALESCE(SUM(
            CASE
                WHEN status IN ('COMPLETED', 'ABORTED') THEN actualFocusMs
                ELSE 0
            END
        ), 0) AS totalFocusMs
        FROM sessions
        WHERE categoryId = :categoryId
          AND startedAtUtcMs >= :fromUtcMs
          AND startedAtUtcMs < :toUtcMs
          AND status IN ('COMPLETED', 'ABORTED')
        """,
    )
    suspend fun dailyProgressMs(categoryId: String, fromUtcMs: Long, toUtcMs: Long): Long

    @Query(
        """
        SELECT COALESCE(SUM(
            CASE
                WHEN sessions.status IN ('COMPLETED', 'ABORTED') THEN sessions.actualFocusMs
                ELSE 0
            END
        ), 0)
        FROM sessions
        JOIN task_preset_tags ON task_preset_tags.presetId = sessions.presetId
        WHERE task_preset_tags.categoryId = :categoryId
          AND sessions.startedAtUtcMs >= :fromUtcMs
          AND sessions.startedAtUtcMs < :toUtcMs
          AND sessions.status IN ('COMPLETED', 'ABORTED')
        """,
    )
    suspend fun dailyProgressForTagMs(categoryId: String, fromUtcMs: Long, toUtcMs: Long): Long

    @Query(
        """
        SELECT CAST((startedAtUtcMs - :rangeStartMs) / 86400000 AS INT) AS dayIndex,
               COALESCE(SUM(actualFocusMs), 0) AS totalMs
        FROM sessions
        WHERE startedAtUtcMs >= :rangeStartMs
          AND startedAtUtcMs < :rangeEndMs
          AND status IN ('COMPLETED', 'ABORTED')
        GROUP BY dayIndex
        ORDER BY dayIndex
        """,
    )
    suspend fun focusByDayIndex(rangeStartMs: Long, rangeEndMs: Long): List<DayFocusRow>
}

/**
 * Data access operations for granular timer activity segments (warmup and focus intervals).
 *
 * Segments link to parent sessions via foreign keys with cascade deletion. Used for exact
 * cross-midnight and hourly report aggregations.
 */
@Dao
internal interface SessionSegmentDao {
    @Upsert
    suspend fun upsert(segment: SessionSegmentEntity)

    @Upsert
    suspend fun upsertAll(segments: List<SessionSegmentEntity>)

    @Query(
        "SELECT * FROM session_segments " +
            "WHERE sessionId = :sessionId AND endedAtUtcMs IS NULL LIMIT 1"
    )
    suspend fun openForSession(sessionId: String): SessionSegmentEntity?

    @Query("DELETE FROM session_segments WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    @Query(
        "SELECT * FROM session_segments " +
            "WHERE startedAtUtcMs < :toUtcMs AND COALESCE(endedAtUtcMs, :toUtcMs) > :fromUtcMs " +
            "ORDER BY startedAtUtcMs"
    )
    suspend fun overlapping(fromUtcMs: Long, toUtcMs: Long): List<SessionSegmentEntity>

    @Query("SELECT * FROM session_segments ORDER BY startedAtUtcMs, id")
    suspend fun allForExport(): List<SessionSegmentEntity>
}

/**
 * Singleton table storing the in-flight timer state for process death and reboot recovery.
 *
 * Contains exactly zero or one row with singletonId = 1, managed inside Room transactions
 * by TimerTransitionStore.
 */
@Dao
internal interface TimerCheckpointDao {
    @Upsert
    suspend fun upsert(checkpoint: TimerCheckpointEntity)

    @Query("SELECT * FROM timer_checkpoint WHERE singletonId = 1 LIMIT 1")
    suspend fun get(): TimerCheckpointEntity?

    @Query("DELETE FROM timer_checkpoint")
    suspend fun delete()
}

/**
 * Data access operations for background image assets.
 *
 * Tracks unique media files by SHA-256 checksum and reference counts for cleanup.
 */
@Dao
internal interface AssetDao {
    @Upsert
    suspend fun upsert(asset: AssetEntity)

    @Upsert
    suspend fun upsertAll(assets: List<AssetEntity>)

    @Query("SELECT * FROM assets WHERE sha256 = :sha256 LIMIT 1")
    suspend fun findBySha256(sha256: String): AssetEntity?

    @Query("SELECT * FROM assets WHERE id = :id LIMIT 1")
    suspend fun get(id: String): AssetEntity?

    @Query("SELECT * FROM assets ORDER BY id")
    suspend fun allForExport(): List<AssetEntity>
}

/**
 * Data access operations for user reflections keyed by local calendar day (ISO-8601 string).
 */
@Dao
internal interface DailyReflectionDao {
    @Query("SELECT * FROM daily_reflections WHERE localDateIso = :date LIMIT 1")
    suspend fun get(date: String): DailyReflectionEntity?

    @Upsert
    suspend fun upsert(reflection: DailyReflectionEntity)

    @Upsert
    suspend fun upsertAll(reflections: List<DailyReflectionEntity>)

    @Query("DELETE FROM daily_reflections WHERE localDateIso = :date")
    suspend fun delete(date: String)

    @Query("SELECT * FROM daily_reflections ORDER BY localDateIso, updatedAtUtcMs")
    suspend fun allForExport(): List<DailyReflectionEntity>
}
