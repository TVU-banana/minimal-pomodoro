package com.akira.clock.export

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.akira.clock.data.ClockDatabase
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ClockBackupService(
    private val context: Context,
    private val database: ClockDatabase,
) {
    suspend fun exportArchive(destination: Uri) = withContext(Dispatchers.IO) {
        val bundle = readBundle()
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        val stream = requireNotNull(context.contentResolver.openOutputStream(destination, "w"))
        stream.use { ClockBackupArchive.write(bundle, version, Instant.now(), it) }
    }

    suspend fun exportCsv(destination: Uri) = withContext(Dispatchers.IO) {
        val sessions = database.sessionDao().allForExport()
        val stream = requireNotNull(context.contentResolver.openOutputStream(destination, "w"))
        stream.bufferedWriter(Charsets.UTF_8).use { it.write(SessionCsvExporter.export(sessions)) }
    }

    suspend fun importArchive(source: Uri) = withContext(Dispatchers.IO) {
        val bytes = requireNotNull(context.contentResolver.openInputStream(source)).use { it.readBytes() }
        val validated = ClockBackupArchive.read(bytes)
        val incoming = validated.bundle
        database.withTransaction {
            val existing = readBundle()
            val merged = BackupMerger.merge(existing, incoming)
            database.categoryDao().upsertAll(merged.categories)
            database.taskPresetDao().upsertAll(merged.presets)
            database.sessionDao().upsertAll(merged.sessions)
            database.sessionSegmentDao().upsertAll(merged.segments)
            database.assetDao().upsertAll(merged.assets)
        }
        // Media has already passed SHA-256 validation. Orphan files are safe if later DB work fails.
        val backgroundDir = java.io.File(context.filesDir, "backgrounds").apply { mkdirs() }
        incoming.media.forEach { (digest, content) ->
            val destination = java.io.File(backgroundDir, "$digest.webp")
            if (!destination.exists()) destination.writeBytes(content)
        }
    }

    private suspend fun readBundle(): BackupBundle = BackupBundle(
        categories = database.categoryDao().allForExport(),
        presets = database.taskPresetDao().allForExport(),
        sessions = database.sessionDao().allForExport(),
        segments = database.sessionSegmentDao().allForExport(),
        assets = database.assetDao().allForExport(),
        media = database.assetDao().allForExport().mapNotNull { asset ->
            val file = java.io.File(context.filesDir, asset.localPath)
            if (file.isFile) asset.sha256 to file.readBytes() else null
        }.toMap(),
    )
}
