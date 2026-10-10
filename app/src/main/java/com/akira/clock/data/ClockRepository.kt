package com.akira.clock.data

import com.akira.clock.report.DailyReflectionText
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Application-facing data operations that do not belong to timer transition transactions. */
internal class ClockRepository(private val database: ClockDatabase) {
    fun observeCategories(): Flow<List<CategoryEntity>> = database.categoryDao().observeActive()
    fun observePresets(): Flow<List<TaskPresetEntity>> = database.taskPresetDao().observeAll()

    /**
     * Composite presentation model combining a preset with its primary and linked category tags.
     */
    data class TimeBlock(
        val preset: TaskPresetEntity,
        val primaryCategory: CategoryEntity,
        val tags: List<CategoryEntity>,
    )

    fun observeTimeBlocks(): Flow<List<TimeBlock>> =
        combine(
            database.categoryDao().observeActive(),
            database.taskPresetDao().observeAll(),
            database.taskPresetTagDao().observeAll(),
        ) { categories, presets, presetTags ->
            val categoriesById = categories.associateBy { it.id }
            val tagsByPreset = presetTags.groupBy { it.presetId }
            presets.mapNotNull { preset ->
                val primary = categoriesById[preset.categoryId] ?: return@mapNotNull null
                TimeBlock(
                    preset = preset,
                    primaryCategory = primary,
                    tags = tagsByPreset[preset.id].orEmpty().mapNotNull {
                        categoriesById[it.categoryId]
                    }.ifEmpty { listOf(primary) },
                )
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
        dailyGoalMs: Long = 3 * 60 * 60_000L,
    ): CategoryEntity {
        require(name.isNotBlank()) { "Tag name is blank" }
        require((colorArgb ushr 24).toInt() == 0xFF) { "Color must include full alpha" }
        require(dailyGoalMs in 60_000L..86_400_000L) { "Daily goal is invalid" }
        val tag = CategoryEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            colorArgb = colorArgb,
            totalDurationMs = 60 * 60_000L,
            warmupDurationMs = 15 * 60_000L,
            dailyGoalMs = dailyGoalMs,
            createdAtUtcMs = System.currentTimeMillis(),
        )
        database.categoryDao().upsert(tag)
        return tag
    }

    /** Legacy duration fields remain unchanged for backup compatibility; presets own timing. */
    suspend fun updateTag(
        id: String,
        name: String,
        colorArgb: Long,
        dailyGoalMs: Long,
    ): CategoryEntity {
        require(name.isNotBlank()) { "Tag name is blank" }
        require((colorArgb ushr 24).toInt() == 0xFF) { "Color must include full alpha" }
        require(dailyGoalMs in 60_000L..86_400_000L) { "Daily goal is invalid" }
        val existing = database.categoryDao().get(id)
            ?: throw IllegalArgumentException("Tag not found: $id")
        val tag = existing.copy(
            name = name.trim(),
            colorArgb = colorArgb,
            dailyGoalMs = dailyGoalMs,
        )
        database.categoryDao().upsert(tag)
        return tag
    }

    /**
     * Retains category and preset references for backup and session history. Active tag selectors
     * and time blocks whose primary tag is archived stop showing them through their Room flows.
     */
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
        database.taskPresetTagDao().upsertAll(
            listOf(TaskPresetTagEntity(DEFAULT_PRESET_ID, DEFAULT_CATEGORY_ID)),
        )
    }

    suspend fun createPreset(
        name: String,
        categoryId: String,
        tagIds: List<String> = listOf(categoryId),
        totalMinutes: Int,
        warmupMinutes: Int,
        stopwatch: Boolean = false,
        timerStyle: TimerStyle = TimerStyle.DIGITAL,
        backgroundAssetId: String? = null,
        nowUtcMs: Long,
        unnamedTaskName: String,
    ): TaskPresetEntity {
        require(stopwatch || totalMinutes in 1..1_440)
        require(stopwatch || warmupMinutes in 0..1_440)
        val preset = TaskPresetEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { unnamedTaskName },
            categoryId = categoryId,
            totalDurationMs = if (stopwatch) 0L else totalMinutes * 60_000L,
            warmupDurationMs = if (stopwatch) 0L else warmupMinutes * 60_000L,
            timerMode = if (stopwatch) "STOPWATCH" else "COUNTDOWN",
            timerStyle = timerStyle.name,
            backgroundAssetId = backgroundAssetId,
            createdAtUtcMs = nowUtcMs,
            updatedAtUtcMs = nowUtcMs,
        )
        database.taskPresetDao().upsert(preset)
        replacePresetTags(preset.id, tagIds.ifEmpty { listOf(categoryId) })
        return preset
    }

    suspend fun updatePreset(
        id: String,
        name: String,
        totalMinutes: Int,
        warmupMinutes: Int,
        stopwatch: Boolean = false,
        tagIds: List<String>? = null,
    ) {
        require(stopwatch || totalMinutes in 1..1_440)
        require(stopwatch || warmupMinutes in 0..1_440)
        val existing = database.taskPresetDao().get(id)
            ?: throw IllegalArgumentException("Preset not found: $id")
        val selectedTagIds = tagIds?.distinct()?.ifEmpty { listOf(existing.categoryId) }
        val primaryCategoryId = selectedTagIds?.first() ?: existing.categoryId
        require(database.categoryDao().get(primaryCategoryId) != null) { "Tag not found" }
        database.taskPresetDao().upsert(
            existing.copy(
                name = name.trim(),
                categoryId = primaryCategoryId,
                totalDurationMs = if (stopwatch) 0L else totalMinutes * 60_000L,
                warmupDurationMs = if (stopwatch) 0L else warmupMinutes * 60_000L,
                timerMode = if (stopwatch) "STOPWATCH" else "COUNTDOWN",
                updatedAtUtcMs = System.currentTimeMillis(),
            ),
        )
        if (selectedTagIds != null) replacePresetTags(id, selectedTagIds)
    }

    suspend fun deletePreset(id: String) {
        database.taskPresetDao().delete(id)
    }

    suspend fun listTagIdsForPreset(presetId: String): List<String> =
        database.taskPresetTagDao().listForPreset(presetId).map { it.categoryId }

    private suspend fun replacePresetTags(presetId: String, tagIds: List<String>) {
        val distinctIds = tagIds.distinct()
        require(distinctIds.isNotEmpty()) { "A time block requires one tag" }
        require(distinctIds.all { database.categoryDao().get(it) != null }) { "Tag not found" }
        database.taskPresetTagDao().deleteForPreset(presetId)
        database.taskPresetTagDao().upsertAll(distinctIds.map { TaskPresetTagEntity(presetId, it) })
    }

    fun currentTimezoneId(): String = ZoneId.systemDefault().id

    /** Saves one bounded reflection for [date], deleting the row when its text is blank. */
    suspend fun saveDailyReflection(date: LocalDate, text: String, nowUtcMs: Long): String {
        val normalized = DailyReflectionText.normalize(text)
        val dao = database.dailyReflectionDao()
        if (normalized.isEmpty()) {
            dao.delete(date.toString())
            return normalized
        }
        require(DailyReflectionText.isValid(normalized)) { "Reflection is too long" }
        val existing = dao.get(date.toString())
        dao.upsert(
            DailyReflectionEntity(
                localDateIso = date.toString(),
                timezoneIdAtWrite = currentTimezoneId(),
                text = normalized,
                createdAtUtcMs = existing?.createdAtUtcMs ?: nowUtcMs,
                updatedAtUtcMs = nowUtcMs,
            ),
        )
        return normalized
    }

    suspend fun dailyReflection(date: LocalDate): String =
        database.dailyReflectionDao().get(date.toString())?.text.orEmpty()

    companion object {
        const val DEFAULT_CATEGORY_ID = "00000000-0000-4000-8000-000000000001"
        const val DEFAULT_PRESET_ID = "00000000-0000-4000-8000-000000000002"
    }
}
