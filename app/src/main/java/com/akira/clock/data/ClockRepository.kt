package com.akira.clock.data

import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Application-facing data operations that do not belong to timer transition transactions. */
internal class ClockRepository(private val database: ClockDatabase) {
    fun observeCategories(): Flow<List<CategoryEntity>> = database.categoryDao().observeActive()
    fun observePresets(): Flow<List<TaskPresetEntity>> = database.taskPresetDao().observeAll()

    data class TagGroup(
        val category: CategoryEntity,
        val presets: List<TaskPresetEntity>,
    )

    fun observeTagGroups(): Flow<List<TagGroup>> =
        combine(
            database.categoryDao().observeActive(),
            database.taskPresetDao().observeAll(),
        ) { categories, presets ->
            val presetsByCat = presets.groupBy { it.categoryId }
            categories.map { cat ->
                TagGroup(cat, presetsByCat[cat.id].orEmpty())
            }
        }

    fun observePresetsForCategory(categoryId: String): Flow<List<TaskPresetEntity>> =
        database.taskPresetDao().observeByCategory(categoryId)

    suspend fun listPresetsForCategory(categoryId: String): List<TaskPresetEntity> =
        database.taskPresetDao().listByCategory(categoryId)

    /** Tags are categories surfaced to the user as the timer's primary label. */
    fun observeTags(): Flow<List<CategoryEntity>> = database.categoryDao().observeActive()

    suspend fun createTag(
        name: String,
        colorArgb: Long,
        totalDurationMs: Long,
        warmupDurationMs: Long,
        dailyGoalMs: Long = 3 * 60 * 60_000L,
    ): CategoryEntity {
        require(name.isNotBlank()) { "Tag name is blank" }
        require((colorArgb ushr 24).toInt() == 0xFF) { "Color must include full alpha" }
        require(totalDurationMs > 0) { "Total duration must be positive" }
        require(warmupDurationMs in 0 until totalDurationMs) { "Warmup must be below total" }
        val tag = CategoryEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            colorArgb = colorArgb,
            totalDurationMs = totalDurationMs,
            warmupDurationMs = warmupDurationMs,
            dailyGoalMs = dailyGoalMs,
            createdAtUtcMs = System.currentTimeMillis(),
        )
        database.categoryDao().upsert(tag)
        return tag
    }

    suspend fun updateTag(
        id: String,
        name: String,
        colorArgb: Long,
        totalDurationMs: Long,
        warmupDurationMs: Long,
        dailyGoalMs: Long? = null,
    ): CategoryEntity {
        require(name.isNotBlank()) { "Tag name is blank" }
        require((colorArgb ushr 24).toInt() == 0xFF) { "Color must include full alpha" }
        require(totalDurationMs > 0) { "Total duration must be positive" }
        require(warmupDurationMs in 0 until totalDurationMs) { "Warmup must be below total" }
        val existing = database.categoryDao().get(id)
            ?: throw IllegalArgumentException("Tag not found: $id")
        val tag = CategoryEntity(
            id = id,
            name = name.trim(),
            colorArgb = colorArgb,
            totalDurationMs = totalDurationMs,
            warmupDurationMs = warmupDurationMs,
            dailyGoalMs = dailyGoalMs ?: existing.dailyGoalMs,
            createdAtUtcMs = existing.createdAtUtcMs,
        )
        database.categoryDao().upsert(tag)
        return tag
    }

    suspend fun deleteTag(id: String) {
        database.categoryDao().archive(id, System.currentTimeMillis())
    }

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
                    totalDurationMs = 60 * 60_000L,
                    warmupDurationMs = 15 * 60_000L,
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

    suspend fun updatePreset(
        id: String,
        name: String,
        totalMinutes: Int,
        warmupMinutes: Int,
    ) {
        require(totalMinutes in 1..1_440)
        require(warmupMinutes in 0 until totalMinutes)
        val existing = database.taskPresetDao().get(id)
            ?: throw IllegalArgumentException("Preset not found: $id")
        database.taskPresetDao().upsert(
            existing.copy(
                name = name.trim(),
                totalDurationMs = totalMinutes * 60_000L,
                warmupDurationMs = warmupMinutes * 60_000L,
                updatedAtUtcMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deletePreset(id: String) {
        database.taskPresetDao().delete(id)
    }

    fun currentTimezoneId(): String = ZoneId.systemDefault().id

    companion object {
        const val DEFAULT_CATEGORY_ID = "00000000-0000-4000-8000-000000000001"
        const val DEFAULT_PRESET_ID = "00000000-0000-4000-8000-000000000002"
    }
}
