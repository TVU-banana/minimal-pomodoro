package com.akira.clock.export

import com.akira.clock.data.CategoryEntity
import com.akira.clock.data.AssetEntity
import com.akira.clock.data.SessionEntity
import com.akira.clock.data.SessionPhase
import com.akira.clock.data.SessionSegmentEntity
import com.akira.clock.data.SessionStatus
import com.akira.clock.data.TaskPresetEntity
import com.akira.clock.data.TimerStyle
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

data class OpenBackupFiles(
    val categoriesJson: ByteArray,
    val presetsJson: ByteArray,
    val sessionsNdjson: ByteArray,
    val segmentsNdjson: ByteArray,
    val assetsJson: ByteArray,
) {
    fun asMap(): Map<String, ByteArray> = linkedMapOf(
        CATEGORIES_FILE to categoriesJson,
        PRESETS_FILE to presetsJson,
        SESSIONS_FILE to sessionsNdjson,
        SEGMENTS_FILE to segmentsNdjson,
        ASSETS_FILE to assetsJson,
    )
}

object OpenBackupCodec {
    fun encode(bundle: BackupBundle): OpenBackupFiles {
        val normalized = BackupValidator.validate(bundle).normalized()
        return OpenBackupFiles(
            categoriesJson = encodeArray(normalized.categories.map(::categoryJson)),
            presetsJson = encodeArray(normalized.presets.map(::presetJson)),
            sessionsNdjson = normalized.sessions.joinToString("\n", postfix = if (normalized.sessions.isEmpty()) "" else "\n", transform = ::sessionJson)
                .toByteArray(StandardCharsets.UTF_8),
            segmentsNdjson = normalized.segments.joinToString("\n", postfix = if (normalized.segments.isEmpty()) "" else "\n", transform = ::segmentJson)
                .toByteArray(StandardCharsets.UTF_8),
            assetsJson = encodeArray(normalized.assets.map(::assetJson)),
        )
    }

    fun decode(files: Map<String, ByteArray>): BackupBundle {
        val categories = decodeArray(requireFile(files, CATEGORIES_FILE), CATEGORIES_FILE, ::parseCategory)
        val presets = decodeArray(requireFile(files, PRESETS_FILE), PRESETS_FILE, ::parsePreset)
        val sessionsSource = requireFile(files, SESSIONS_FILE).toString(StandardCharsets.UTF_8)
        val sessions = if (sessionsSource.isBlank()) emptyList() else sessionsSource.lineSequence()
            .filter { it.isNotBlank() }
            .mapIndexed { index, line ->
                try {
                    parseSession(JsonCodec.parse(line).objectValue("session"))
                } catch (failure: BackupFormatException) {
                    throw BackupFormatException("Invalid $SESSIONS_FILE line ${index + 1}: ${failure.message}", failure)
                }
            }.toList()
        val segmentsSource = files[SEGMENTS_FILE]?.toString(StandardCharsets.UTF_8).orEmpty()
        val segments = if (segmentsSource.isBlank()) emptyList() else segmentsSource.lineSequence()
            .filter { it.isNotBlank() }
            .mapIndexed { index, line ->
                try {
                    parseSegment(JsonCodec.parse(line).objectValue("segment"))
                } catch (failure: BackupFormatException) {
                    throw BackupFormatException("Invalid $SEGMENTS_FILE line ${index + 1}: ${failure.message}", failure)
                }
            }.toList()
        val assets = files[ASSETS_FILE]?.let { decodeArray(it, ASSETS_FILE, ::parseAsset) }.orEmpty()
        return BackupValidator.validate(BackupBundle(categories, presets, sessions, segments, assets)).normalized()
    }

    private fun categoryJson(value: CategoryEntity): String = objectJson(
        "id" to string(value.id),
        "name" to string(value.name),
        "colorArgb" to value.colorArgb.toString(),
        "createdAt" to instant(value.createdAtUtcMs),
        "archivedAt" to nullableInstant(value.archivedAtUtcMs),
    )

    private fun presetJson(value: TaskPresetEntity): String = objectJson(
        "id" to string(value.id),
        "name" to string(value.name),
        "categoryId" to string(value.categoryId),
        "totalDurationMs" to value.totalDurationMs.toString(),
        "warmupDurationMs" to value.warmupDurationMs.toString(),
        "timerStyle" to string(value.timerStyle),
        "backgroundAssetId" to nullableString(value.backgroundAssetId),
        "createdAt" to instant(value.createdAtUtcMs),
        "updatedAt" to instant(value.updatedAtUtcMs),
    )

    private fun sessionJson(value: SessionEntity): String = objectJson(
        "id" to string(value.id),
        "presetId" to nullableString(value.presetId),
        "categoryId" to string(value.categoryId),
        "categoryNameSnapshot" to string(value.categoryNameSnapshot),
        "taskNameSnapshot" to string(value.taskNameSnapshot),
        "plannedTotalMs" to value.plannedTotalMs.toString(),
        "plannedWarmupMs" to value.plannedWarmupMs.toString(),
        "actualWarmupMs" to value.actualWarmupMs.toString(),
        "actualFocusMs" to value.actualFocusMs.toString(),
        "startedAt" to instant(value.startedAtUtcMs),
        "endedAt" to nullableInstant(value.endedAtUtcMs),
        "timezoneId" to string(value.timezoneId),
        "status" to string(value.status),
        "createdAt" to instant(value.createdAtUtcMs),
        "updatedAt" to instant(value.updatedAtUtcMs),
    )

    private fun segmentJson(value: SessionSegmentEntity): String = objectJson(
        "id" to string(value.id),
        "sessionId" to string(value.sessionId),
        "phase" to string(value.phase),
        "startedAt" to instant(value.startedAtUtcMs),
        "endedAt" to nullableInstant(value.endedAtUtcMs),
    )

    private fun assetJson(value: AssetEntity): String = objectJson(
        "id" to string(value.id),
        "sha256" to string(value.sha256),
        "width" to value.width.toString(),
        "height" to value.height.toString(),
        "referenceCount" to value.referenceCount.toString(),
        "createdAt" to instant(value.createdAtUtcMs),
    )

    private fun parseCategory(fields: Map<String, JsonValue>) = CategoryEntity(
        id = fields.requiredString("id"),
        name = fields.requiredString("name"),
        colorArgb = fields.requiredLong("colorArgb"),
        createdAtUtcMs = fields.requiredInstant("createdAt"),
        archivedAtUtcMs = fields.optionalInstant("archivedAt"),
    )

    private fun parsePreset(fields: Map<String, JsonValue>) = TaskPresetEntity(
        id = fields.requiredString("id"),
        name = fields.requiredString("name"),
        categoryId = fields.requiredString("categoryId"),
        totalDurationMs = fields.requiredLong("totalDurationMs"),
        warmupDurationMs = fields.requiredLong("warmupDurationMs"),
        timerStyle = fields.requiredString("timerStyle"),
        backgroundAssetId = fields.optionalString("backgroundAssetId"),
        createdAtUtcMs = fields.requiredInstant("createdAt"),
        updatedAtUtcMs = fields.requiredInstant("updatedAt"),
    )

    private fun parseSession(fields: Map<String, JsonValue>) = SessionEntity(
        id = fields.requiredString("id"),
        presetId = fields.optionalString("presetId"),
        categoryId = fields.requiredString("categoryId"),
        categoryNameSnapshot = fields.requiredString("categoryNameSnapshot"),
        taskNameSnapshot = fields.optionalString("taskNameSnapshot").orEmpty(),
        plannedTotalMs = fields.requiredLong("plannedTotalMs"),
        plannedWarmupMs = fields.requiredLong("plannedWarmupMs"),
        actualWarmupMs = fields.requiredLong("actualWarmupMs"),
        actualFocusMs = fields.requiredLong("actualFocusMs"),
        startedAtUtcMs = fields.requiredInstant("startedAt"),
        endedAtUtcMs = fields.optionalInstant("endedAt"),
        timezoneId = fields.requiredString("timezoneId"),
        status = fields.requiredString("status"),
        createdAtUtcMs = fields.requiredInstant("createdAt"),
        updatedAtUtcMs = fields.requiredInstant("updatedAt"),
    )

    private fun parseSegment(fields: Map<String, JsonValue>) = SessionSegmentEntity(
        id = fields.requiredString("id"),
        sessionId = fields.requiredString("sessionId"),
        phase = fields.requiredString("phase"),
        startedAtUtcMs = fields.requiredInstant("startedAt"),
        endedAtUtcMs = fields.optionalInstant("endedAt"),
    )

    private fun parseAsset(fields: Map<String, JsonValue>) = AssetEntity(
        id = fields.requiredString("id"),
        sha256 = fields.requiredString("sha256"),
        localPath = "backgrounds/${fields.requiredString("sha256")}.webp",
        width = fields.requiredLong("width").toInt(),
        height = fields.requiredLong("height").toInt(),
        referenceCount = fields.requiredLong("referenceCount").toInt(),
        createdAtUtcMs = fields.requiredInstant("createdAt"),
    )

    private fun <T> decodeArray(bytes: ByteArray, name: String, transform: (Map<String, JsonValue>) -> T): List<T> =
        JsonCodec.parse(bytes.toString(StandardCharsets.UTF_8)).arrayValue(name)
            .mapIndexed { index, value -> transform(value.objectValue("$name[$index]")) }

    private fun encodeArray(objects: List<String>): ByteArray =
        objects.joinToString(prefix = "[", postfix = "]", separator = ",").toByteArray(StandardCharsets.UTF_8)

    private fun objectJson(vararg fields: Pair<String, String>): String = fields.joinToString(
        prefix = "{",
        postfix = "}",
        separator = ",",
    ) { (name, value) -> "${JsonCodec.quote(name)}:$value" }

    private fun string(value: String) = JsonCodec.quote(value)
    private fun nullableString(value: String?) = value?.let(JsonCodec::quote) ?: "null"
    private fun instant(epochMs: Long) = JsonCodec.quote(Instant.ofEpochMilli(epochMs).toString())
    private fun nullableInstant(epochMs: Long?) = epochMs?.let(::instant) ?: "null"

    private fun requireFile(files: Map<String, ByteArray>, name: String): ByteArray =
        files[name] ?: throw BackupFormatException("Missing required file: $name")
}

object BackupValidator {
    fun validate(source: BackupBundle): BackupBundle {
        uniqueIds("category", source.categories.map { it.id })
        uniqueIds("preset", source.presets.map { it.id })
        uniqueIds("session", source.sessions.map { it.id })
        uniqueIds("segment", source.segments.map { it.id })
        uniqueIds("asset", source.assets.map { it.id })
        val categoryIds = source.categories.mapTo(hashSetOf()) { it.id }
        val presetIds = source.presets.mapTo(hashSetOf()) { it.id }
        val sessionIds = source.sessions.mapTo(hashSetOf()) { it.id }
        val assetIds = source.assets.mapTo(hashSetOf()) { it.id }

        source.categories.forEach {
            requireFormat(it.id.isNotBlank(), "Category id is blank")
            requireFormat(it.name.isNotBlank(), "Category ${it.id} name is blank")
            requireFormat(it.createdAtUtcMs >= 0, "Category ${it.id} timestamp is invalid")
        }
        source.presets.forEach {
            requireFormat(it.categoryId in categoryIds, "Preset ${it.id} references missing category")
            requireFormat(it.totalDurationMs > 0, "Preset ${it.id} total duration is invalid")
            requireFormat(it.warmupDurationMs in 0 until it.totalDurationMs, "Preset ${it.id} warmup is invalid")
            requireFormat(TimerStyle.entries.any { style -> style.name == it.timerStyle }, "Preset ${it.id} style is invalid")
            requireFormat(it.backgroundAssetId == null || it.backgroundAssetId in assetIds, "Preset ${it.id} references missing asset")
        }
        source.sessions.forEach {
            requireFormat(it.categoryId in categoryIds, "Session ${it.id} references missing category")
            requireFormat(it.presetId == null || it.presetId in presetIds, "Session ${it.id} references missing preset")
            requireFormat(it.plannedTotalMs > 0, "Session ${it.id} total duration is invalid")
            requireFormat(it.plannedWarmupMs in 0 until it.plannedTotalMs, "Session ${it.id} warmup is invalid")
            requireFormat(it.actualWarmupMs >= 0 && it.actualFocusMs >= 0, "Session ${it.id} actual duration is invalid")
            requireFormat(SessionStatus.entries.any { status -> status.name == it.status }, "Session ${it.id} status is invalid")
            requireFormat(it.endedAtUtcMs == null || it.endedAtUtcMs >= it.startedAtUtcMs, "Session ${it.id} end is invalid")
        }
        source.segments.forEach {
            requireFormat(it.sessionId in sessionIds, "Segment ${it.id} references missing session")
            requireFormat(SessionPhase.entries.any { phase -> phase.name == it.phase }, "Segment ${it.id} phase is invalid")
            requireFormat(it.endedAtUtcMs == null || it.endedAtUtcMs >= it.startedAtUtcMs, "Segment ${it.id} end is invalid")
        }
        source.assets.forEach {
            requireFormat(it.sha256.matches(Regex("[0-9a-f]{64}")), "Asset ${it.id} hash is invalid")
            requireFormat(it.width > 0 && it.height > 0, "Asset ${it.id} dimensions are invalid")
            requireFormat(it.referenceCount >= 0, "Asset ${it.id} reference count is invalid")
        }
        source.media.forEach { (digest, bytes) ->
            requireFormat(digest.matches(Regex("[0-9a-f]{64}")), "Invalid media name: $digest")
            requireFormat(digest == sha256(bytes), "Media checksum does not match its name: $digest")
        }
        return source
    }

    private fun uniqueIds(kind: String, ids: List<String>) {
        val duplicate = ids.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        requireFormat(duplicate == null, "Duplicate $kind id: $duplicate")
    }
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

private fun requireFormat(condition: Boolean, message: String) {
    if (!condition) throw BackupFormatException(message)
}

private fun Map<String, JsonValue>.requiredInstant(name: String): Long = try {
    val source = requiredString(name)
    if (!source.endsWith('Z')) throw IllegalArgumentException("UTC designator required")
    Instant.parse(source).toEpochMilli()
} catch (failure: Exception) {
    throw BackupFormatException("$name must be an ISO-8601 UTC timestamp", failure)
}

private fun Map<String, JsonValue>.optionalInstant(name: String): Long? = optionalString(name)?.let {
    try {
        if (!it.endsWith('Z')) throw IllegalArgumentException("UTC designator required")
        Instant.parse(it).toEpochMilli()
    } catch (failure: Exception) {
        throw BackupFormatException("$name must be an ISO-8601 UTC timestamp or null", failure)
    }
}

const val MANIFEST_FILE = "manifest.json"
const val CATEGORIES_FILE = "categories.json"
const val PRESETS_FILE = "presets.json"
const val SESSIONS_FILE = "sessions.ndjson"
const val SEGMENTS_FILE = "segments.ndjson"
const val ASSETS_FILE = "assets.json"
