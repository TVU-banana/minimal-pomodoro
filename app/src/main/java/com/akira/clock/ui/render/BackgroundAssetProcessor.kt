package com.akira.clock.ui.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.roundToInt

internal data class ProcessedBackgroundAsset(
    val sha256: String,
    val file: File,
    val width: Int,
    val height: Int,
)

/** Downsamples, normalizes, hashes, and stores one private WebP background. */
internal class BackgroundAssetProcessor(private val context: Context) {
    suspend fun process(
        source: Uri,
        maxWidth: Int,
        maxHeight: Int,
        quality: Int = DEFAULT_QUALITY,
    ): ProcessedBackgroundAsset = withContext(Dispatchers.IO) {
        processBlocking(source, maxWidth, maxHeight, quality)
    }

    private fun processBlocking(
        source: Uri,
        maxWidth: Int,
        maxHeight: Int,
        quality: Int,
    ): ProcessedBackgroundAsset {
        require(maxWidth > 0 && maxHeight > 0)
        require(quality in 0..100)
        val bounds = decodeBounds(source)
        require(bounds.first > 0 && bounds.second > 0) { "Unsupported or empty image" }
        val sampleSize = calculateInSampleSize(bounds.first, bounds.second, maxWidth, maxHeight)
        val decoded = context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "Unable to open selected image" }
            BitmapFactory.decodeStream(
                input,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } ?: error("Unable to decode selected image")

        val targetSize = calculateTargetSize(decoded.width, decoded.height, maxWidth, maxHeight)
        val bitmap = if (decoded.width == targetSize.first && decoded.height == targetSize.second) {
            decoded
        } else {
            decoded.scale(targetSize.first, targetSize.second).also {
                decoded.recycle()
            }
        }

        val directory = File(context.filesDir, BACKGROUNDS_DIRECTORY).apply { mkdirs() }
        val temporary = File.createTempFile("background-", ".webp.tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                check(bitmap.compress(webpFormat(), quality, output)) { "WebP encoding failed" }
            }
            val sha256 = sha256(temporary)
            val destination = File(directory, "$sha256.webp")
            if (destination.exists()) {
                temporary.delete()
            } else {
                check(temporary.renameTo(destination)) { "Unable to store processed background" }
            }
            return ProcessedBackgroundAsset(sha256, destination, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun decodeBounds(source: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "Unable to open selected image" }
            BitmapFactory.decodeStream(input, null, options)
        }
        return options.outWidth to options.outHeight
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        FileInputStream(file).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }

    companion object {
        const val BACKGROUNDS_DIRECTORY = "backgrounds"
        const val DEFAULT_QUALITY = 85

        fun calculateInSampleSize(
            sourceWidth: Int,
            sourceHeight: Int,
            maxWidth: Int,
            maxHeight: Int,
        ): Int {
            require(sourceWidth > 0 && sourceHeight > 0)
            require(maxWidth > 0 && maxHeight > 0)
            var sample = 1
            while (sourceWidth / (sample * 2) >= maxWidth &&
                sourceHeight / (sample * 2) >= maxHeight
            ) {
                sample *= 2
            }
            return sample
        }

        fun calculateTargetSize(
            sourceWidth: Int,
            sourceHeight: Int,
            maxWidth: Int,
            maxHeight: Int,
        ): Pair<Int, Int> {
            require(sourceWidth > 0 && sourceHeight > 0)
            require(maxWidth > 0 && maxHeight > 0)
            if (sourceWidth <= maxWidth && sourceHeight <= maxHeight) {
                return sourceWidth to sourceHeight
            }
            val scale = minOf(
                maxWidth.toDouble() / sourceWidth,
                maxHeight.toDouble() / sourceHeight,
            )
            return maxOf(1, (sourceWidth * scale).roundToInt()) to
                maxOf(1, (sourceHeight * scale).roundToInt())
        }
    }
}
