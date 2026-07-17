package com.akira.clock.export

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockBackupArchiveTest {
    private val exportedAt = Instant.parse("2026-07-15T10:30:00Z")

    @Test
    fun `archive and checksums are deterministic`() {
        val first = ClockBackupArchive.writeToBytes(sampleBackup(), "0.3.0", exportedAt)
        val second = ClockBackupArchive.writeToBytes(sampleBackup(), "0.3.0", exportedAt)

        assertEquals(first.toList(), second.toList())
        assertEquals(sampleBackup().normalized(), ClockBackupArchive.read(first).bundle)
    }

    @Test
    fun `checksum mismatch rejects archive before importer is called`() {
        val valid = ClockBackupArchive.writeToBytes(sampleBackup(), "0.3.0", exportedAt)
        val entries = unzip(valid)
        entries[CATEGORIES_FILE] = entries.getValue(CATEGORIES_FILE) + ' '.code.toByte()
        val corrupt = zip(entries)

        var databaseWriteCalled = false
        val coordinator = ClockBackupImportCoordinator { databaseWriteCalled = true }

        assertThrows(BackupFormatException::class.java) { runBlocking { coordinator.import(corrupt) } }
        assertFalse(databaseWriteCalled)
    }

    @Test
    fun `unknown major schema is rejected`() {
        val valid = ClockBackupArchive.writeToBytes(sampleBackup(), "0.3.0", exportedAt)
        val entries = unzip(valid)
        entries[MANIFEST_FILE] = entries.getValue(MANIFEST_FILE).toString(StandardCharsets.UTF_8)
            .replace("\"schemaVersion\":1", "\"schemaVersion\":2")
            .toByteArray(StandardCharsets.UTF_8)

        val failure = assertThrows(BackupFormatException::class.java) { ClockBackupArchive.read(zip(entries)) }
        assertTrue(failure.message.orEmpty().contains("Unsupported backup schema"))
    }

    private fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes), StandardCharsets.UTF_8).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
            }
        }
        return result
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output, StandardCharsets.UTF_8).use { zip ->
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    crc = CRC32().apply { update(bytes) }.value
                    time = 0
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()
}
