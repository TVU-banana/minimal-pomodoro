package com.akira.clock.export

import com.akira.clock.data.CategoryEntity
import com.akira.clock.data.AssetEntity
import com.akira.clock.data.SessionEntity
import com.akira.clock.data.SessionSegmentEntity
import com.akira.clock.data.TaskPresetEntity

internal const val BACKUP_FORMAT = "clock-backup"
internal const val BACKUP_SCHEMA_VERSION = 1

internal data class BackupBundle(
    val categories: List<CategoryEntity>,
    val presets: List<TaskPresetEntity>,
    val sessions: List<SessionEntity>,
    val segments: List<SessionSegmentEntity> = emptyList(),
    val assets: List<AssetEntity> = emptyList(),
    val media: Map<String, ByteArray> = emptyMap(),
) {
    fun normalized(): BackupBundle = copy(
        categories = categories.sortedBy { it.id },
        presets = presets.sortedBy { it.id },
        sessions = sessions.sortedWith(compareBy(SessionEntity::startedAtUtcMs, SessionEntity::id)),
        segments = segments.sortedWith(compareBy(SessionSegmentEntity::startedAtUtcMs, SessionSegmentEntity::id)),
        assets = assets.sortedBy { it.id },
        media = media.toSortedMap(),
    )

    override fun equals(other: Any?): Boolean =
        other is BackupBundle &&
            categories == other.categories && presets == other.presets && sessions == other.sessions && segments == other.segments && assets == other.assets &&
            media.keys == other.media.keys && media.all { (key, value) ->
                value.contentEquals(other.media.getValue(key))
            }

    override fun hashCode(): Int = 31 * categories.hashCode() + 31 * presets.hashCode() + 31 * sessions.hashCode() + 31 * segments.hashCode() + assets.hashCode()
}

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

/** Conflict policy for an AtomicBackupWriter: keep the record with the newest update timestamp. */
internal object BackupMerger {
    fun merge(existing: BackupBundle, incoming: BackupBundle): BackupBundle = BackupValidator.validate(
        BackupBundle(
            categories = mergeBy(existing.categories, incoming.categories, { it.id }, { it.createdAtUtcMs }),
            presets = mergeBy(existing.presets, incoming.presets, { it.id }, { it.updatedAtUtcMs }),
            sessions = mergeBy(existing.sessions, incoming.sessions, { it.id }, { it.updatedAtUtcMs }),
            segments = mergeBy(existing.segments, incoming.segments, { it.id }, { it.endedAtUtcMs ?: Long.MAX_VALUE }),
            assets = mergeBy(existing.assets, incoming.assets, { it.id }, { it.createdAtUtcMs }),
            media = existing.media + incoming.media,
        ),
    ).normalized()

    private fun <T> mergeBy(existing: List<T>, incoming: List<T>, id: (T) -> String, revision: (T) -> Long): List<T> =
        (existing + incoming).groupBy(id).values.map { candidates -> candidates.maxBy(revision) }
}
