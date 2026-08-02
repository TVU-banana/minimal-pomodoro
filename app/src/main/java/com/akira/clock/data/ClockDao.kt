package com.akira.clock.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

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

@Dao
internal interface TaskPresetDao {
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

@Dao
internal interface SessionDao {
    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Upsert
    suspend fun upsertAll(sessions: List<SessionEntity>)

    @Query("SELECT * FROM sessions ORDER BY startedAtUtcMs DESC, id DESC")
    fun observeAll(): Flow<List<SessionEntity>>

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
                WHEN status = 'COMPLETED' THEN plannedTotalMs
                WHEN status = 'ABORTED' AND actualFocusMs >= plannedWarmupMs THEN actualFocusMs
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
        SELECT CAST((startedAtUtcMs - :dayStartMs) / 3600000 AS INT) AS hourIndex,
               COALESCE(SUM(actualFocusMs), 0) AS totalMs
        FROM sessions
        WHERE startedAtUtcMs >= :dayStartMs
          AND startedAtUtcMs < :dayEndMs
          AND status IN ('COMPLETED', 'ABORTED')
        GROUP BY hourIndex
        ORDER BY hourIndex
        """,
    )
    suspend fun focusByHour(dayStartMs: Long, dayEndMs: Long): List<HourFocusRow>

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

@Dao
internal interface SessionSegmentDao {
    @Upsert
    suspend fun upsert(segment: SessionSegmentEntity)

    @Upsert
    suspend fun upsertAll(segments: List<SessionSegmentEntity>)

    @Query("SELECT * FROM session_segments WHERE sessionId = :sessionId AND endedAtUtcMs IS NULL LIMIT 1")
    suspend fun openForSession(sessionId: String): SessionSegmentEntity?

    @Query("DELETE FROM session_segments WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    @Query("SELECT * FROM session_segments WHERE startedAtUtcMs < :toUtcMs AND COALESCE(endedAtUtcMs, :toUtcMs) > :fromUtcMs ORDER BY startedAtUtcMs")
    suspend fun overlapping(fromUtcMs: Long, toUtcMs: Long): List<SessionSegmentEntity>

    @Query("SELECT * FROM session_segments ORDER BY startedAtUtcMs, id")
    suspend fun allForExport(): List<SessionSegmentEntity>
}

@Dao
internal interface TimerCheckpointDao {
    @Upsert
    suspend fun upsert(checkpoint: TimerCheckpointEntity)

    @Query("SELECT * FROM timer_checkpoint WHERE singletonId = 1 LIMIT 1")
    suspend fun get(): TimerCheckpointEntity?

    @Query("DELETE FROM timer_checkpoint")
    suspend fun delete()
}

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
