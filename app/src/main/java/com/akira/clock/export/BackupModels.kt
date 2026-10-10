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

internal const val BACKUP_FORMAT = "clock-backup"
internal const val BACKUP_SCHEMA_VERSION = 4

/** Complete in-memory representation of a Clock backup archive before or after serialization. */
internal data class BackupBundle(
    val categories: List<CategoryEntity>,
    val presets: List<TaskPresetEntity>,
    val sessions: List<SessionEntity>,
    val segments: List<SessionSegmentEntity> = emptyList(),
    val assets: List<AssetEntity> = emptyList(),
    val reflections: List<DailyReflectionEntity> = emptyList(),
    val media: Map<String, ByteArray> = emptyMap(),
    val habits: List<HabitEntity> = emptyList(),
    val habitCheckIns: List<HabitCheckInEntity> = emptyList(),
    val habitTargets: List<HabitTargetEntity> = emptyList(),
) {
    fun normalized(): BackupBundle = copy(
        habitTargets = habitTargets.sortedWith(
            compareBy(HabitTargetEntity::habitId, HabitTargetEntity::effectiveLocalDateIso),
        ),
        categories = categories.sortedBy { it.id },
        presets = presets.sortedBy { it.id },
        sessions = sessions.sortedWith(compareBy(SessionEntity::startedAtUtcMs, SessionEntity::id)),
        segments = segments.sortedWith(
            compareBy(SessionSegmentEntity::startedAtUtcMs, SessionSegmentEntity::id),
        ),
        assets = assets.sortedBy { it.id },
        reflections = reflections.sortedBy { it.localDateIso },
        media = media.toSortedMap(),
        habits = habits.sortedBy { it.id },
        habitCheckIns = habitCheckIns.sortedWith(
            compareBy(HabitCheckInEntity::habitId, HabitCheckInEntity::localDateIso),
        ),
    )

    override fun equals(other: Any?): Boolean =
        other is BackupBundle &&
            categories == other.categories && presets == other.presets &&
            sessions == other.sessions && segments == other.segments &&
            assets == other.assets && reflections == other.reflections &&
            habits == other.habits && habitCheckIns == other.habitCheckIns &&
            habitTargets == other.habitTargets &&
            media.keys == other.media.keys && media.all { (key, value) ->
                value.contentEquals(other.media.getValue(key))
            }

    override fun hashCode(): Int = 31 * categories.hashCode() + 31 * presets.hashCode() +
        31 * sessions.hashCode() + 31 * segments.hashCode() + 31 * assets.hashCode() +
        31 * reflections.hashCode() + 31 * habits.hashCode() +
        31 * habitCheckIns.hashCode() + habitTargets.hashCode()
}

/** Deserialized metadata and file checksum index parsed from manifest.json. */
internal data class BackupManifest(
    val schemaVersion: Int,
    val appVersion: String,
    val exportedAt: String,
    val files: Map<String, String>,
)

/** Fully parsed and validated import. No database writes happen while creating this value. */
internal data class ValidatedBackup(val manifest: BackupManifest, val bundle: BackupBundle)

internal class BackupFormatException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Implement this with one Room `withTransaction` block. */
internal fun interface AtomicBackupWriter {
    suspend fun write(bundle: BackupBundle)
}

internal fun interface BackupImporter {
    suspend fun import(validated: ValidatedBackup)
}

internal class AtomicBackupImporter(private val writer: AtomicBackupWriter) : BackupImporter {
    override suspend fun import(validated: ValidatedBackup) = writer.write(validated.bundle)
}

/** Parsing and validation finish before the importer (and therefore Room) is invoked. */
internal class ClockBackupImportCoordinator(private val importer: BackupImporter) {
    suspend fun import(bytes: ByteArray) {
        val validated = ClockBackupArchive.read(bytes)
        importer.import(validated)
    }
}

/**
 * Keeps the newest revision for each stable key; equal revisions retain the existing local value.
 *
 * Creation metadata is immutable. A conflicting origin rejects the import before any writes so
 * another definition cannot silently change which historical dates are applicable.
 *
 * Check-in undo rows remain in the merge so an older completed row cannot resurrect completion.
 */
internal object BackupMerger {
    fun merge(existing: BackupBundle, incoming: BackupBundle): BackupBundle {
        requireCompatibleHabitOrigins(existing.habits, incoming.habits)
        val merged = BackupBundle(
            categories = mergeBy(
                existing.categories,
                incoming.categories,
                { it.id },
                { it.createdAtUtcMs },
            ),
            presets = mergeBy(existing.presets, incoming.presets, { it.id }, { it.updatedAtUtcMs }),
            sessions = mergeBy(
                existing.sessions,
                incoming.sessions,
                { it.id },
                { it.updatedAtUtcMs },
            ),
            segments = mergeBy(
                existing.segments,
                incoming.segments,
                { it.id },
                { it.endedAtUtcMs ?: Long.MAX_VALUE },
            ),
            assets = mergeBy(existing.assets, incoming.assets, { it.id }, { it.createdAtUtcMs }),
            reflections = mergeBy(
                existing.reflections,
                incoming.reflections,
                { it.localDateIso },
                { it.updatedAtUtcMs },
            ),
            media = existing.media + incoming.media,
            habits = mergeBy(
                existing.habits,
                incoming.habits,
                { it.id },
                { it.updatedAtUtcMs },
            ),
            habitTargets = mergeBy(
                existing.habitTargets, incoming.habitTargets,
                { it.habitId to it.effectiveLocalDateIso }, { it.updatedAtUtcMs },
            ),
            habitCheckIns = mergeBy(
                existing.habitCheckIns,
                incoming.habitCheckIns,
                { it.habitId to it.localDateIso },
                { it.updatedAtUtcMs },
            ),
        )
        return BackupValidator.validate(merged).normalized()
    }

    private fun requireCompatibleHabitOrigins(
        existing: List<HabitEntity>,
        incoming: List<HabitEntity>,
    ) {
        val byId = existing.associateBy { it.id }
        incoming.forEach { habit ->
            val local = byId[habit.id]
            if (
                local != null &&
                    (habit.createdLocalDateIso != local.createdLocalDateIso ||
                        habit.createdAtUtcMs != local.createdAtUtcMs)
            ) {
                throw BackupFormatException("Habit ${habit.id} has conflicting creation metadata")
            }
        }
    }

    private fun <T, K> mergeBy(
        existing: List<T>,
        incoming: List<T>,
        id: (T) -> K,
        revision: (T) -> Long,
    ): List<T> =
        (existing + incoming).groupBy(id).values.map { candidates -> candidates.maxBy(revision) }
}
