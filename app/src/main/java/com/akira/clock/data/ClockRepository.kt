package com.akira.clock.data

import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Application-facing data operations that do not belong to timer transition transactions. */
internal class ClockRepository(private val database: ClockDatabase) {
    fun observeCategories(): Flow<List<CategoryEntity>> = database.categoryDao().observeActive()
    fun observePresets(): Flow<List<TaskPresetEntity>> = database.taskPresetDao().observeAll()
    fun observeSessions(): Flow<List<SessionEntity>> = database.sessionDao().observeAll()

    suspend fun ensureDefaults(
        nowUtcMs: Long,
        defaultCategoryName: String,
        defaultPresetName: String,
    ) {
        if (database.categoryDao().get(DEFAULT_CATEGORY_ID) == null) {
            database.categoryDao().upsert(
                CategoryEntity(
                    id = DEFAULT_CATEGORY_ID,
                    name = defaultCategoryName,
                    colorArgb = 0xFFD95D39,
                    createdAtUtcMs = nowUtcMs,
                ),
            )
        }
        if (database.taskPresetDao().get(DEFAULT_PRESET_ID) == null) {
            database.taskPresetDao().upsert(
                TaskPresetEntity(
                    id = DEFAULT_PRESET_ID,
                    name = defaultPresetName,
                    categoryId = DEFAULT_CATEGORY_ID,
                    totalDurationMs = 60 * 60_000L,
                    warmupDurationMs = 15 * 60_000L,
                    createdAtUtcMs = nowUtcMs,
                    updatedAtUtcMs = nowUtcMs,
                ),
            )
        }
    }

    suspend fun createPreset(
        name: String,
        categoryId: String,
        totalMinutes: Int,
        warmupMinutes: Int,
        timerStyle: TimerStyle = TimerStyle.DIGITAL,
        backgroundAssetId: String? = null,
        nowUtcMs: Long,
        unnamedTaskName: String,
    ): TaskPresetEntity {
        require(totalMinutes in 1..1_440)
        require(warmupMinutes in 0 until totalMinutes)
        val preset = TaskPresetEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { unnamedTaskName },
            categoryId = categoryId,
            totalDurationMs = totalMinutes * 60_000L,
            warmupDurationMs = warmupMinutes * 60_000L,
            timerStyle = timerStyle.name,
            backgroundAssetId = backgroundAssetId,
            createdAtUtcMs = nowUtcMs,
            updatedAtUtcMs = nowUtcMs,
        )
        database.taskPresetDao().upsert(preset)
        return preset
    }

    fun currentTimezoneId(): String = ZoneId.systemDefault().id

    companion object {
        const val DEFAULT_CATEGORY_ID = "00000000-0000-4000-8000-000000000001"
        const val DEFAULT_PRESET_ID = "00000000-0000-4000-8000-000000000002"
    }
}
