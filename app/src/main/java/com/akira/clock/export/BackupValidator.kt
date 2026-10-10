package com.akira.clock.export

import com.akira.clock.data.SessionPhase
import com.akira.clock.data.SessionStatus
import com.akira.clock.data.TimerStyle
import com.akira.clock.report.DailyReflectionText
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

/**
 * Validates referential integrity and field constraints before backup data reaches Room.
 *
 * Validation is side-effect free. Import code must complete this step before opening its database
 * transaction so a malformed archive cannot partially change local data.
 */
internal object BackupValidator {
    fun validate(source: BackupBundle): BackupBundle {
        requireUniqueIds("category", source.categories.map { it.id })
        requireUniqueIds("preset", source.presets.map { it.id })
        requireUniqueIds("session", source.sessions.map { it.id })
        requireUniqueIds("segment", source.segments.map { it.id })
        requireUniqueIds("asset", source.assets.map { it.id })
        requireUniqueIds("daily reflection", source.reflections.map { it.localDateIso })
        requireUniqueIds("habit", source.habits.map { it.id })
        requireUniqueIds(
            "habit check-in",
            source.habitCheckIns.map { it.habitId to it.localDateIso },
        )

        requireUniqueIds(
            "habit target", source.habitTargets.map { it.habitId to it.effectiveLocalDateIso },
        )
        val categoryIds = source.categories.mapTo(hashSetOf()) { it.id }
        val presetIds = source.presets.mapTo(hashSetOf()) { it.id }
        val sessionIds = source.sessions.mapTo(hashSetOf()) { it.id }
        val assetIds = source.assets.mapTo(hashSetOf()) { it.id }
        val habitIds = source.habits.mapTo(hashSetOf()) { it.id }

        source.categories.forEach { category ->
            requireFormat(category.id.isNotBlank(), "Category id is blank")
            requireFormat(category.name.isNotBlank(), "Category ${category.id} name is blank")
            requireFormat(
                category.createdAtUtcMs >= 0,
                "Category ${category.id} timestamp is invalid",
            )
        }
        source.presets.forEach { preset ->
            requireFormat(
                preset.categoryId in categoryIds,
                "Preset ${preset.id} references missing category",
            )
            requireFormat(preset.totalDurationMs > 0, "Preset ${preset.id} duration is invalid")
            requireFormat(
                preset.warmupDurationMs in 0..86_400_000L,
                "Preset ${preset.id} warmup is invalid",
            )
            requireFormat(
                TimerStyle.entries.any { style -> style.name == preset.timerStyle },
                "Preset ${preset.id} style is invalid",
            )
            requireFormat(
                preset.backgroundAssetId == null || preset.backgroundAssetId in assetIds,
                "Preset ${preset.id} references missing asset",
            )
        }
        source.sessions.forEach { session ->
            requireFormat(
                session.categoryId in categoryIds,
                "Session ${session.id} references missing category",
            )
            requireFormat(
                session.presetId == null || session.presetId in presetIds,
                "Session ${session.id} references missing preset",
            )
            requireFormat(session.plannedTotalMs > 0, "Session ${session.id} duration is invalid")
            requireFormat(
                session.plannedWarmupMs in 0 until session.plannedTotalMs,
                "Session ${session.id} warmup is invalid",
            )
            requireFormat(
                session.actualWarmupMs >= 0 && session.actualFocusMs >= 0,
                "Session ${session.id} actual duration is invalid",
            )
            requireFormat(
                SessionStatus.entries.any { status -> status.name == session.status },
                "Session ${session.id} status is invalid",
            )
            requireFormat(
                session.endedAtUtcMs == null || session.endedAtUtcMs >= session.startedAtUtcMs,
                "Session ${session.id} end is invalid",
            )
        }
        source.segments.forEach { segment ->
            requireFormat(
                segment.sessionId in sessionIds,
                "Segment ${segment.id} references missing session",
            )
            requireFormat(
                SessionPhase.entries.any { phase -> phase.name == segment.phase },
                "Segment ${segment.id} phase is invalid",
            )
            requireFormat(
                segment.endedAtUtcMs == null || segment.endedAtUtcMs >= segment.startedAtUtcMs,
                "Segment ${segment.id} end is invalid",
            )
        }
        source.assets.forEach { asset ->
            requireFormat(
                asset.sha256.matches(SHA_256_PATTERN),
                "Asset ${asset.id} hash is invalid",
            )
            requireFormat(asset.width > 0 && asset.height > 0, "Asset dimensions are invalid")
            requireFormat(asset.referenceCount >= 0, "Asset reference count is invalid")
        }
        source.reflections.forEach { reflection ->
            requireFormat(
                runCatching { LocalDate.parse(reflection.localDateIso) }.isSuccess,
                "Reflection date is invalid",
            )
            requireFormat(
                runCatching { ZoneId.of(reflection.timezoneIdAtWrite) }.isSuccess,
                "Reflection timezone is invalid",
            )
            requireFormat(
                DailyReflectionText.isValid(reflection.text),
                "Reflection text is invalid",
            )
            requireFormat(
                reflection.createdAtUtcMs >= 0 &&
                    reflection.updatedAtUtcMs >= reflection.createdAtUtcMs,
                "Reflection timestamp is invalid",
            )
        }
        source.habits.forEach { habit ->
            requireFormat(habit.id.matches(UUID_PATTERN), "Habit id is invalid")
            requireFormat(
                habit.name == habit.name.trim() && habit.name.length in 1..120,
                "Habit ${habit.id} name is invalid",
            )
            requireFormat(
                habit.note == habit.note.trim() && habit.note.length <= 1000,
                "Habit ${habit.id} note is invalid",
            )
            requireFormat(habit.sortOrder >= 0, "Habit ${habit.id} sort order is invalid")
            val createdDate = requireLocalDate(habit.createdLocalDateIso, "Habit created date")
            habit.archivedLocalDateIso?.let { archived ->
                val archivedDate = requireLocalDate(archived, "Habit archived date")
                requireFormat(archivedDate >= createdDate, "Habit archive predates creation")
            }
            requireFormat(
                habit.createdAtUtcMs >= 0 && habit.updatedAtUtcMs >= habit.createdAtUtcMs,
                "Habit ${habit.id} timestamp is invalid",
            )
        }
        source.habitTargets.forEach { target ->
            requireFormat(target.habitId in habitIds, "Target references missing habit")
            val date = requireLocalDate(target.effectiveLocalDateIso, "Target effective date")
            val habit = source.habits.first { it.id == target.habitId }
            requireFormat(date >= LocalDate.parse(habit.createdLocalDateIso),
                "Target predates habit creation")
            requireFormat(target.targetCount in 1..99, "Habit target is invalid")
            requireFormat(target.updatedAtUtcMs >= 0, "Target timestamp is invalid")
        }
        source.habitCheckIns.forEach { checkIn ->
            requireFormat(checkIn.habitId.matches(UUID_PATTERN), "Check-in habit id is invalid")
            requireFormat(checkIn.habitId in habitIds, "Check-in references missing habit")
            requireLocalDate(checkIn.localDateIso, "Check-in date")
            requireFormat(checkIn.completedCount in 0..99, "Check-in count is invalid")
            requireFormat(!checkIn.completed || checkIn.completedCount > 0,
                "Completed check-in has no repetitions")
            requireFormat(checkIn.updatedAtUtcMs >= 0, "Check-in timestamp is invalid")
            requireFormat(
                if (checkIn.completed) {
                    checkIn.completedAtUtcMs != null &&
                        checkIn.completedAtUtcMs in 0..checkIn.updatedAtUtcMs
                } else {
                    checkIn.completedAtUtcMs == null
                },
                "Check-in completion timestamp does not match its state",
            )
        }
        source.media.forEach { (digest, bytes) ->
            requireFormat(digest.matches(SHA_256_PATTERN), "Invalid media name: $digest")
            requireFormat(
                digest == sha256(bytes),
                "Media checksum does not match its name: $digest",
            )
        }
        return source
    }

    private fun <K> requireUniqueIds(kind: String, ids: List<K>) {
        val duplicate = ids.groupingBy { it }
            .eachCount()
            .entries
            .firstOrNull { it.value > 1 }
            ?.key
        requireFormat(duplicate == null, "Duplicate $kind id: $duplicate")
    }

    private fun requireLocalDate(value: String, name: String): LocalDate {
        requireFormat(value.matches(LOCAL_DATE_PATTERN), "$name must be yyyy-MM-dd")
        return try {
            LocalDate.parse(value)
        } catch (failure: Exception) {
            throw BackupFormatException("$name is not a valid calendar date", failure)
        }
    }

    private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
    private val LOCAL_DATE_PATTERN = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    private val UUID_PATTERN = Regex(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
    )
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

private fun requireFormat(condition: Boolean, message: String) {
    if (!condition) throw BackupFormatException(message)
}
