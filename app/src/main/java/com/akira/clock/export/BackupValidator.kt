package com.akira.clock.export

import com.akira.clock.data.SessionPhase
import com.akira.clock.data.SessionStatus
import com.akira.clock.data.TimerStyle
import java.security.MessageDigest

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

        val categoryIds = source.categories.mapTo(hashSetOf()) { it.id }
        val presetIds = source.presets.mapTo(hashSetOf()) { it.id }
        val sessionIds = source.sessions.mapTo(hashSetOf()) { it.id }
        val assetIds = source.assets.mapTo(hashSetOf()) { it.id }

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
                preset.warmupDurationMs in 0 until preset.totalDurationMs,
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
        source.media.forEach { (digest, bytes) ->
            requireFormat(digest.matches(SHA_256_PATTERN), "Invalid media name: $digest")
            requireFormat(
                digest == sha256(bytes),
                "Media checksum does not match its name: $digest",
            )
        }
        return source
    }

    private fun requireUniqueIds(kind: String, ids: List<String>) {
        val duplicate = ids.groupingBy { it }
            .eachCount()
            .entries
            .firstOrNull { it.value > 1 }
            ?.key
        requireFormat(duplicate == null, "Duplicate $kind id: $duplicate")
    }

    private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

private fun requireFormat(condition: Boolean, message: String) {
    if (!condition) throw BackupFormatException(message)
}
