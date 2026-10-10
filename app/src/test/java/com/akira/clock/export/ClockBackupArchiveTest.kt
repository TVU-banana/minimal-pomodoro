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
    fun archiveAndChecksumsAreDeterministic() {
        val first = ClockBackupArchive.writeToBytes(sampleBackup(), "0.3.0", exportedAt)
        val second = ClockBackupArchive.writeToBytes(sampleBackup(), "0.3.0", exportedAt)

        assertEquals(first.toList(), second.toList())
        assertEquals(sampleBackup().normalized(), ClockBackupArchive.read(first).bundle)
    }

    @Test
    fun checksumMismatchRejectsArchiveBeforeImport() {
        val valid = ClockBackupArchive.writeToBytes(sampleBackup(), "0.3.0", exportedAt)
        val entries = unzip(valid)
        entries[CATEGORIES_FILE] = entries.getValue(CATEGORIES_FILE) + ' '.code.toByte()
        val corrupt = zip(entries)

        var databaseWriteCalled = false
        val coordinator = ClockBackupImportCoordinator { databaseWriteCalled = true }

        assertThrows(BackupFormatException::class.java) {
            runBlocking { coordinator.import(corrupt) }
        }
        assertFalse(databaseWriteCalled)
    }

    @Test
    fun unknownMajorSchemaIsRejected() {
        val valid = ClockBackupArchive.writeToBytes(sampleBackup(), "0.3.0", exportedAt)
        val entries = unzip(valid)
        entries[MANIFEST_FILE] = entries.getValue(MANIFEST_FILE).toString(StandardCharsets.UTF_8)
            .replace("\"schemaVersion\":4", "\"schemaVersion\":5")
            .toByteArray(StandardCharsets.UTF_8)

        val failure = assertThrows(BackupFormatException::class.java) {
            ClockBackupArchive.read(zip(entries))
        }
        assertTrue(failure.message.orEmpty().contains("Unsupported backup schema"))
    }

    @Test
    fun versionOneArchiveWithoutReflectionsRemainsReadable() {
        val entries = legacyEntries(1)

        val restored = ClockBackupArchive.read(zip(entries))

        assertTrue(restored.bundle.reflections.isEmpty())
        assertTrue(restored.bundle.habits.isEmpty())
        assertTrue(restored.bundle.habitCheckIns.isEmpty())
        assertEquals(1, restored.manifest.schemaVersion)
    }

    @Test
    fun versionTwoArchiveKeepsReflectionsAndHasEmptyHabits() {
        val restored = ClockBackupArchive.read(zip(legacyEntries(2)))

        assertEquals(sampleBackup().reflections, restored.bundle.reflections)
        assertTrue(restored.bundle.habits.isEmpty())
        assertTrue(restored.bundle.habitCheckIns.isEmpty())
        assertEquals(2, restored.manifest.schemaVersion)
    }

    @Test
    fun versionThreeRoundTripIncludesArchivedHabitsAndUndoRows() {
        val bundle = sampleHabitBackup()
        val archive = ClockBackupArchive.writeToBytes(bundle, "1.0.1", exportedAt)
        val restored = ClockBackupArchive.read(archive)

        assertEquals(4, restored.manifest.schemaVersion)
        assertEquals(bundle.normalized(), restored.bundle)
        assertTrue(HABITS_FILE in restored.manifest.files)
        assertTrue(HABIT_CHECK_INS_FILE in restored.manifest.files)
        assertEquals(
            sha256(unzip(archive).getValue(HABITS_FILE)),
            restored.manifest.files[HABITS_FILE],
        )
        assertEquals(
            sha256(unzip(archive).getValue(HABIT_CHECK_INS_FILE)),
            restored.manifest.files[HABIT_CHECK_INS_FILE],
        )
    }

    @Test
    fun legacyVersionThreeWithoutCountsOrTargetsPreservesOneTimeHistory() {
        val bundle = sampleHabitBackup()
        val entries = unzip(ClockBackupArchive.writeToBytes(bundle, "1.0.1", exportedAt))
        removeListedFile(entries, HABIT_TARGETS_FILE)
        val oldDigest = sha256(entries.getValue(HABIT_CHECK_INS_FILE))
        entries[HABIT_CHECK_INS_FILE] = entries.getValue(HABIT_CHECK_INS_FILE).decodeToString()
            .replace(Regex(",\"completedCount\":\\d+"), "").encodeToByteArray()
        entries[MANIFEST_FILE] = entries.getValue(MANIFEST_FILE).decodeToString()
            .replace("\"schemaVersion\":4", "\"schemaVersion\":3")
            .replace(oldDigest, sha256(entries.getValue(HABIT_CHECK_INS_FILE)))
            .encodeToByteArray()
        assertEquals(bundle.normalized(), ClockBackupArchive.read(zip(entries)).bundle)
    }

    @Test
    fun versionThreeRejectsMissingHabitFileEvenWithMatchingManifest() {
        val entries = unzip(
            ClockBackupArchive.writeToBytes(sampleHabitBackup(), "1.0.1", exportedAt),
        )
        removeListedFile(entries, HABITS_FILE)

        val failure = assertThrows(BackupFormatException::class.java) {
            ClockBackupArchive.read(zip(entries))
        }

        assertTrue(failure.message.orEmpty().contains(HABITS_FILE))
    }

    @Test
    fun invalidHabitReferencesRejectTheEntireImportBeforeWrites() {
        val entries = unzip(
            ClockBackupArchive.writeToBytes(sampleHabitBackup(), "1.0.1", exportedAt),
        )
        val orphan = entries.getValue(HABIT_CHECK_INS_FILE).toString(StandardCharsets.UTF_8)
            .replace("10289410-c0d8-4d10-b480-b1b575355c03", "90289410-c0d8-4d10-b480-b1b575355c03")
            .toByteArray(StandardCharsets.UTF_8)
        val oldDigest = sha256(entries.getValue(HABIT_CHECK_INS_FILE))
        entries[HABIT_CHECK_INS_FILE] = orphan
        entries[MANIFEST_FILE] = entries.getValue(MANIFEST_FILE).toString(StandardCharsets.UTF_8)
            .replace(oldDigest, sha256(orphan))
            .toByteArray(StandardCharsets.UTF_8)
        var writes = 0
        val coordinator = ClockBackupImportCoordinator { writes++ }

        assertThrows(BackupFormatException::class.java) {
            runBlocking { coordinator.import(zip(entries)) }
        }

        assertEquals(0, writes)
    }

    private fun legacyEntries(version: Int): LinkedHashMap<String, ByteArray> {
        val entries = unzip(ClockBackupArchive.writeToBytes(sampleBackup(), "0.3.0", exportedAt))
        removeListedFile(entries, HABIT_CHECK_INS_FILE)
        removeListedFile(entries, HABITS_FILE)
        removeListedFile(entries, HABIT_TARGETS_FILE)
        if (version == 1) removeListedFile(entries, REFLECTIONS_FILE)
        entries[MANIFEST_FILE] = entries.getValue(MANIFEST_FILE).toString(StandardCharsets.UTF_8)
            .replace("\"schemaVersion\":4", "\"schemaVersion\":$version")
            .toByteArray(StandardCharsets.UTF_8)
        return entries
    }

    private fun removeListedFile(entries: MutableMap<String, ByteArray>, name: String) {
        entries.remove(name)
        val field = ",${Regex.escape(JsonCodec.quote(name))}:\\{\"sha256\":\"[0-9a-f]+\"}"
        entries[MANIFEST_FILE] = entries.getValue(MANIFEST_FILE).toString(StandardCharsets.UTF_8)
            .replace(Regex(field), "")
            .toByteArray(StandardCharsets.UTF_8)
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

    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also {
        output ->
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
