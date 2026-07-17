package com.akira.clock.data

import android.content.Context
import android.net.Uri
import com.akira.clock.ui.render.BackgroundAssetProcessor
import java.io.File
import java.util.UUID

/** Result of importing and deduplicating one user-selected background. */
internal data class StoredBackgroundAsset(
    val id: String,
    val file: File,
)

/**
 * Imports background images into private storage and registers them in Room.
 *
 * Image decoding and hashing are delegated to [BackgroundAssetProcessor]. The SHA-256 digest is the
 * deduplication key, so selecting the same image does not create another stored file.
 */
internal class BackgroundAssetRepository(
    context: Context,
    private val database: ClockDatabase,
) {
    private val applicationContext = context.applicationContext
    private val processor = BackgroundAssetProcessor(applicationContext)

    suspend fun import(source: Uri, maximumEdge: Int): StoredBackgroundAsset {
        val processed = processor.process(source, maximumEdge, maximumEdge)
        val asset = database.assetDao().findBySha256(processed.sha256) ?: AssetEntity(
            id = UUID.randomUUID().toString(),
            sha256 = processed.sha256,
            localPath = "backgrounds/${processed.file.name}",
            width = processed.width,
            height = processed.height,
            referenceCount = 1,
            createdAtUtcMs = System.currentTimeMillis(),
        ).also { database.assetDao().upsert(it) }
        return StoredBackgroundAsset(asset.id, processed.file)
    }
}
