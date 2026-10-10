package com.akira.clock.export

import com.akira.clock.data.CategoryEntity
import com.akira.clock.data.DailyReflectionEntity
import com.akira.clock.data.HabitCheckInEntity
import com.akira.clock.data.HabitEntity
import com.akira.clock.data.SessionEntity
import com.akira.clock.data.SessionStatus
import com.akira.clock.data.TaskPresetEntity
import java.time.Instant

internal fun sampleBackup(categoryName: String = "学习"): BackupBundle {
    val category = CategoryEntity(
        id = "1bd29857-5503-4f9a-b811-0f5591f0d245",
        name = categoryName,
        colorArgb = 0xff334455,
        totalDurationMs = 3_600_000,
        warmupDurationMs = 900_000,
        createdAtUtcMs = 1_752_541_200_000,
    )
    val preset = TaskPresetEntity(
        id = "33bf0148-e2d0-46da-aed6-b1ca34c56cf7",
        name = "深度学习",
        categoryId = category.id,
        totalDurationMs = 3_600_000,
        warmupDurationMs = 900_000,
        createdAtUtcMs = 1_752_541_200_000,
        updatedAtUtcMs = 1_752_541_200_000,
    )
    val later = sampleSession(
        id = "bd58d6b5-5019-4d3c-a601-3b05fcc88192",
        category = category,
        preset = preset,
        startedAt = 1_752_544_860_000,
    )
    val earlier = sampleSession(
        id = "bd58d6b5-5019-4d3c-a601-3b05fcc88191",
        category = category,
        preset = preset,
        startedAt = 1_752_541_260_000,
    )
    return BackupBundle(
        categories = listOf(category),
        presets = listOf(preset),
        sessions = listOf(later, earlier),
        reflections = listOf(
            DailyReflectionEntity(
                localDateIso = "2026-07-15",
                timezoneIdAtWrite = "Asia/Shanghai",
                text = "完成了重点任务\n复盘了时间分配 😀",
                createdAtUtcMs = 1_752_541_200_000,
                updatedAtUtcMs = 1_752_541_260_000,
            ),
        ),
    )
}

internal fun sampleSession(
    id: String = "bd58d6b5-5019-4d3c-a601-3b05fcc88191",
    category: CategoryEntity = sampleBackup().categories.single(),
    preset: TaskPresetEntity = sampleBackup().presets.single(),
    startedAt: Long = 1_752_541_260_000,
): SessionEntity = SessionEntity(
    id = id,
    presetId = preset.id,
    categoryId = category.id,
    categoryNameSnapshot = category.name,
    plannedTotalMs = 3_600_000,
    plannedWarmupMs = 900_000,
    actualWarmupMs = 900_000,
    actualFocusMs = 2_700_000,
    startedAtUtcMs = startedAt,
    endedAtUtcMs = startedAt + 3_600_000,
    timezoneId = "Asia/Shanghai",
    status = SessionStatus.COMPLETED.name,
    createdAtUtcMs = startedAt,
    updatedAtUtcMs = startedAt + 3_600_000,
)

internal fun sampleHabitBackup(): BackupBundle {
    val createdAt = Instant.parse("2026-07-13T08:00:00Z").toEpochMilli()
    val completedAt = createdAt + 86_400_000
    val active = HabitEntity(
        id = "10289410-c0d8-4d10-b480-b1b575355c03",
        name = "阅读",
        sortOrder = 1,
        createdLocalDateIso = "2026-07-13",
        createdAtUtcMs = createdAt,
        updatedAtUtcMs = createdAt,
    )
    val archived = active.copy(
        id = "20289410-c0d8-4d10-b480-b1b575355c03",
        name = "散步",
        sortOrder = 0,
        archivedLocalDateIso = "2026-07-15",
        updatedAtUtcMs = completedAt + 86_400_000,
    )
    return sampleBackup().copy(
        habits = listOf(archived, active),
        habitCheckIns = listOf(
            HabitCheckInEntity(
                habitId = active.id,
                localDateIso = "2026-07-15",
                completed = false,
                completedAtUtcMs = null,
                updatedAtUtcMs = completedAt + 86_400_001,
            ),
            HabitCheckInEntity(
                habitId = archived.id,
                localDateIso = "2026-07-14",
                completed = true,
                completedAtUtcMs = completedAt,
                updatedAtUtcMs = completedAt,
            ),
            HabitCheckInEntity(
                habitId = active.id,
                localDateIso = "2026-07-14",
                completed = true,
                completedAtUtcMs = completedAt,
                updatedAtUtcMs = completedAt,
            ),
        ),
    )
}
