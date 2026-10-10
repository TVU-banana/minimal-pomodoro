package com.akira.clock.export

import com.akira.clock.data.AssetEntity
import com.akira.clock.data.CategoryEntity
import com.akira.clock.data.DailyReflectionEntity
import com.akira.clock.data.HabitCheckInEntity
import com.akira.clock.data.HabitEntity
import com.akira.clock.data.HabitTargetEntity
import com.akira.clock.data.SessionEntity
import com.akira.clock.data.SessionSegmentEntity
import com.akira.clock.data.TaskPresetEntity
import java.nio.charset.StandardCharsets
import java.time.Instant

/** Deterministic text files contained in an open Clock backup. */
internal data class OpenBackupFiles(
    val categoriesJson: ByteArray,
    val presetsJson: ByteArray,
    val sessionsNdjson: ByteArray,
    val segmentsNdjson: ByteArray,
    val assetsJson: ByteArray,
    val reflectionsNdjson: ByteArray,
    val habitsJson: ByteArray,
    val habitCheckInsNdjson: ByteArray,
    val habitTargetsJson: ByteArray,
) {
    fun asMap(): Map<String, ByteArray> = linkedMapOf(
        CATEGORIES_FILE to categoriesJson,
        PRESETS_FILE to presetsJson,
        SESSIONS_FILE to sessionsNdjson,
        SEGMENTS_FILE to segmentsNdjson,
        ASSETS_FILE to assetsJson,
        REFLECTIONS_FILE to reflectionsNdjson,
        HABITS_FILE to habitsJson,
        HABIT_CHECK_INS_FILE to habitCheckInsNdjson,
        HABIT_TARGETS_FILE to habitTargetsJson,
    )
}

/** Encodes and decodes the versioned open backup representation. */
internal object OpenBackupCodec {
    fun encode(bundle: BackupBundle): OpenBackupFiles {
        val normalized = BackupValidator.validate(bundle).normalized()
        return OpenBackupFiles(
            categoriesJson = encodeArray(normalized.categories.map(::categoryJson)),
            presetsJson = encodeArray(normalized.presets.map(::presetJson)),
            sessionsNdjson = normalized.sessions.joinToString(
                "\n",
                postfix = if (normalized.sessions.isEmpty()) "" else "\n",
                transform = ::sessionJson,
            ).toByteArray(StandardCharsets.UTF_8),
            segmentsNdjson = normalized.segments.joinToString(
                "\n",
                postfix = if (normalized.segments.isEmpty()) "" else "\n",
                transform = ::segmentJson,
            ).toByteArray(StandardCharsets.UTF_8),
            assetsJson = encodeArray(normalized.assets.map(::assetJson)),
            reflectionsNdjson = normalized.reflections.joinToString(
                "\n",
                postfix = if (normalized.reflections.isEmpty()) "" else "\n",
                transform = ::reflectionJson,
            ).toByteArray(StandardCharsets.UTF_8),
            habitTargetsJson = encodeArray(normalized.habitTargets.map(::habitTargetJson)),
            habitsJson = encodeArray(normalized.habits.map(::habitJson)),
            habitCheckInsNdjson = normalized.habitCheckIns.joinToString(
                "\n",
                postfix = if (normalized.habitCheckIns.isEmpty()) "" else "\n",
                transform = ::habitCheckInJson,
            ).toByteArray(StandardCharsets.UTF_8),
        )
    }

    fun decode(
        files: Map<String, ByteArray>,
        schemaVersion: Int = BACKUP_SCHEMA_VERSION,
    ): BackupBundle {
        val categories = decodeArray(
            requireFile(files, CATEGORIES_FILE),
            CATEGORIES_FILE,
            ::parseCategory,
        )
        val presets = decodeArray(requireFile(files, PRESETS_FILE), PRESETS_FILE, ::parsePreset)
        val sessionsSource = requireFile(files, SESSIONS_FILE).toString(StandardCharsets.UTF_8)
        val sessions = if (sessionsSource.isBlank()) emptyList() else sessionsSource.lineSequence()
            .filter { it.isNotBlank() }
            .mapIndexed { index, line ->
                try {
                    parseSession(JsonCodec.parse(line).objectValue("session"))
                } catch (failure: BackupFormatException) {
                    throw BackupFormatException(
                        "Invalid $SESSIONS_FILE line ${index + 1}: ${failure.message}",
                        failure,
                    )
                }
            }.toList()
        val segmentsSource = files[SEGMENTS_FILE]?.toString(StandardCharsets.UTF_8).orEmpty()
        val segments = if (segmentsSource.isBlank()) emptyList() else segmentsSource.lineSequence()
            .filter { it.isNotBlank() }
            .mapIndexed { index, line ->
                try {
                    parseSegment(JsonCodec.parse(line).objectValue("segment"))
                } catch (failure: BackupFormatException) {
                    throw BackupFormatException(
                        "Invalid $SEGMENTS_FILE line ${index + 1}: ${failure.message}",
                        failure,
                    )
                }
            }.toList()
        val assets = files[ASSETS_FILE]?.let {
            decodeArray(it, ASSETS_FILE, ::parseAsset)
        }.orEmpty()
        val reflectionsSource = files[REFLECTIONS_FILE]?.toString(StandardCharsets.UTF_8).orEmpty()
        val reflections = reflectionsSource.lineSequence()
            .filter { it.isNotBlank() }
            .mapIndexed { index, line ->
                try {
                    parseReflection(JsonCodec.parse(line).objectValue("reflection"))
                } catch (failure: BackupFormatException) {
                    throw BackupFormatException(
                        "Invalid $REFLECTIONS_FILE line ${index + 1}: ${failure.message}",
                        failure,
                    )
                }
            }.toList()
        val habits = if (schemaVersion >= 3) {
            decodeArray(requireFile(files, HABITS_FILE), HABITS_FILE, ::parseHabit)
        } else {
            emptyList()
        }
        val habitCheckIns = if (schemaVersion >= 3) {
            decodeNdjson(
                requireFile(files, HABIT_CHECK_INS_FILE),
                HABIT_CHECK_INS_FILE,
                { parseHabitCheckIn(it, schemaVersion) },
            )
        } else {
            emptyList()
        }
        return BackupValidator.validate(
            BackupBundle(
                categories,
                presets,
                sessions,
                segments,
                assets,
                reflections,
                habits = habits,
                habitCheckIns = habitCheckIns,
                habitTargets = if (schemaVersion >= 4) {
                    decodeArray(requireFile(files, HABIT_TARGETS_FILE),
                        HABIT_TARGETS_FILE, ::parseHabitTarget)
                } else emptyList(),
            ),
        ).normalized()
    }

    private fun categoryJson(value: CategoryEntity): String = objectJson(
        "id" to string(value.id),
        "name" to string(value.name),
        "colorArgb" to value.colorArgb.toString(),
        "totalDurationMs" to string(value.totalDurationMs.toString()),
        "warmupDurationMs" to string(value.warmupDurationMs.toString()),
        "createdAt" to instant(value.createdAtUtcMs),
        "archivedAt" to nullableInstant(value.archivedAtUtcMs),
    )

    private fun presetJson(value: TaskPresetEntity): String = objectJson(
        "id" to string(value.id),
        "name" to string(value.name),
        "categoryId" to string(value.categoryId),
        "totalDurationMs" to value.totalDurationMs.toString(),
        "warmupDurationMs" to value.warmupDurationMs.toString(),
        "timerMode" to string(value.timerMode),
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
        "abortReason" to nullableString(value.abortReason),
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

    private fun reflectionJson(value: DailyReflectionEntity): String = objectJson(
        "localDate" to string(value.localDateIso),
        "timezoneIdAtWrite" to string(value.timezoneIdAtWrite),
        "text" to string(value.text),
        "createdAt" to instant(value.createdAtUtcMs),
        "updatedAt" to instant(value.updatedAtUtcMs),
    )

    private fun habitJson(value: HabitEntity): String = objectJson(
        "id" to string(value.id),
        "name" to string(value.name),
        "note" to string(value.note),
        "sortOrder" to value.sortOrder.toString(),
        "createdLocalDate" to string(value.createdLocalDateIso),
        "createdAt" to instant(value.createdAtUtcMs),
        "updatedAt" to instant(value.updatedAtUtcMs),
        "archivedLocalDate" to nullableString(value.archivedLocalDateIso),
    )

    private fun habitTargetJson(value: HabitTargetEntity): String = objectJson(
        "habitId" to string(value.habitId),
        "effectiveLocalDate" to string(value.effectiveLocalDateIso),
        "targetCount" to value.targetCount.toString(),
        "updatedAt" to instant(value.updatedAtUtcMs),
    )

    private fun parseHabitTarget(fields: Map<String, JsonValue>) = HabitTargetEntity(
        habitId = fields.requiredString("habitId"),
        effectiveLocalDateIso = fields.requiredString("effectiveLocalDate"),
        targetCount = fields.requiredInt("targetCount"),
        updatedAtUtcMs = fields.requiredInstant("updatedAt"),
    )

    private fun habitCheckInJson(value: HabitCheckInEntity): String = objectJson(
        "habitId" to string(value.habitId),
        "localDate" to string(value.localDateIso),
        "completed" to value.completed.toString(),
        "completedCount" to value.completedCount.toString(),
        "completedAt" to nullableInstant(value.completedAtUtcMs),
        "updatedAt" to instant(value.updatedAtUtcMs),
    )

    private fun parseCategory(fields: Map<String, JsonValue>) = CategoryEntity(
        id = fields.requiredString("id"),
        name = fields.requiredString("name"),
        colorArgb = fields.requiredLong("colorArgb"),
        totalDurationMs = fields.optionalString("totalDurationMs")?.toLongOrNull() ?: 3_600_000L,
        warmupDurationMs = fields.optionalString("warmupDurationMs")?.toLongOrNull() ?: 900_000L,
        createdAtUtcMs = fields.requiredInstant("createdAt"),
        archivedAtUtcMs = fields.optionalInstant("archivedAt"),
    )

    private fun parsePreset(fields: Map<String, JsonValue>) = TaskPresetEntity(
        id = fields.requiredString("id"),
        name = fields.requiredString("name"),
        categoryId = fields.requiredString("categoryId"),
        totalDurationMs = fields.requiredLong("totalDurationMs"),
        warmupDurationMs = fields.requiredLong("warmupDurationMs"),
        timerMode = fields.optionalString("timerMode") ?: "COUNTDOWN",
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
        abortReason = fields.optionalString("abortReason"),
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

    private fun parseReflection(fields: Map<String, JsonValue>) = DailyReflectionEntity(
        localDateIso = fields.requiredString("localDate"),
        timezoneIdAtWrite = fields.requiredString("timezoneIdAtWrite"),
        text = fields.requiredString("text"),
        createdAtUtcMs = fields.requiredInstant("createdAt"),
        updatedAtUtcMs = fields.requiredInstant("updatedAt"),
    )

    private fun parseHabit(fields: Map<String, JsonValue>) = HabitEntity(
        id = fields.requiredString("id"),
        name = fields.requiredString("name"),
        sortOrder = fields.requiredInt("sortOrder"),
        createdLocalDateIso = fields.requiredString("createdLocalDate"),
        createdAtUtcMs = fields.requiredInstant("createdAt"),
        updatedAtUtcMs = fields.requiredInstant("updatedAt"),
        archivedLocalDateIso = fields.optionalString("archivedLocalDate"),
        note = fields.optionalString("note").orEmpty(),
    )

    private fun parseHabitCheckIn(
        fields: Map<String, JsonValue>, schemaVersion: Int,
    ) = HabitCheckInEntity(
        habitId = fields.requiredString("habitId"),
        localDateIso = fields.requiredString("localDate"),
        completed = fields.requiredBoolean("completed"),
        completedCount = if (schemaVersion >= 4) fields.requiredInt("completedCount") else {
            if (fields.requiredBoolean("completed")) 1 else 0
        },
        completedAtUtcMs = fields.optionalInstant("completedAt"),
        updatedAtUtcMs = fields.requiredInstant("updatedAt"),
    )

    private fun <T> decodeNdjson(
        bytes: ByteArray,
        name: String,
        transform: (Map<String, JsonValue>) -> T,
    ): List<T> = bytes.toString(StandardCharsets.UTF_8).lineSequence()
        .filter { it.isNotBlank() }
        .mapIndexed { index, line ->
            try {
                transform(JsonCodec.parse(line).objectValue(name))
            } catch (failure: BackupFormatException) {
                throw BackupFormatException("Invalid $name line ${index + 1}", failure)
            }
        }.toList()

    private fun <T> decodeArray(
        bytes: ByteArray,
        name: String,
        transform: (Map<String, JsonValue>) -> T,
    ): List<T> =
        JsonCodec.parse(bytes.toString(StandardCharsets.UTF_8)).arrayValue(name)
            .mapIndexed { index, value -> transform(value.objectValue("$name[$index]")) }

    private fun encodeArray(objects: List<String>): ByteArray =
        objects.joinToString(prefix = "[", postfix = "]", separator = ",")
            .toByteArray(StandardCharsets.UTF_8)

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

private fun Map<String, JsonValue>.requiredInt(name: String): Int {
    val value = requiredLong(name)
    if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        throw BackupFormatException("$name must be a 32-bit integer")
    }
    return value.toInt()
}

private fun Map<String, JsonValue>.requiredInstant(name: String): Long = try {
    val source = requiredString(name)
    if (!source.endsWith('Z')) throw IllegalArgumentException("UTC designator required")
    Instant.parse(source).toEpochMilli()
} catch (failure: Exception) {
    throw BackupFormatException("$name must be an ISO-8601 UTC timestamp", failure)
}

private fun Map<String, JsonValue>.optionalInstant(name: String): Long? =
    optionalString(name)?.let {
        try {
            if (!it.endsWith('Z')) throw IllegalArgumentException("UTC designator required")
            Instant.parse(it).toEpochMilli()
        } catch (failure: Exception) {
            throw BackupFormatException("$name must be an ISO-8601 UTC timestamp or null", failure)
        }
    }

internal const val MANIFEST_FILE = "manifest.json"
internal const val CATEGORIES_FILE = "categories.json"
internal const val PRESETS_FILE = "presets.json"
internal const val SESSIONS_FILE = "sessions.ndjson"
internal const val SEGMENTS_FILE = "segments.ndjson"
internal const val ASSETS_FILE = "assets.json"
internal const val REFLECTIONS_FILE = "reflections.ndjson"
internal const val HABITS_FILE = "habits.json"
internal const val HABIT_CHECK_INS_FILE = "habit_check_ins.ndjson"

internal const val HABIT_TARGETS_FILE = "habit_targets.json"
