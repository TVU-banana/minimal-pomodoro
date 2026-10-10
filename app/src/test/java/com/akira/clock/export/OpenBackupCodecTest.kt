package com.akira.clock.export

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenBackupCodecTest {
    @Test
    fun unknownJsonFieldsAreIgnored() {
        val encoded = OpenBackupCodec.encode(sampleBackup()).asMap().toMutableMap()
        val categories = encoded.getValue(CATEGORIES_FILE).toString(StandardCharsets.UTF_8)
            .replace("}]", ",\"futureField\":{\"enabled\":true}}]")
        encoded[CATEGORIES_FILE] = categories.toByteArray(StandardCharsets.UTF_8)

        val decoded = OpenBackupCodec.decode(encoded)

        assertEquals(sampleBackup().normalized(), decoded)
    }

    @Test
    fun openFilesAreDeterministicAndSessionsAreChronological() {
        val first = OpenBackupCodec.encode(sampleBackup())
        val second = OpenBackupCodec.encode(
            sampleBackup().copy(sessions = sampleBackup().sessions.reversed()),
        )

        assertEquals(first.categoriesJson.toList(), second.categoriesJson.toList())
        assertEquals(first.presetsJson.toList(), second.presetsJson.toList())
        assertEquals(first.sessionsNdjson.toList(), second.sessionsNdjson.toList())
        val lines = first.sessionsNdjson.toString(StandardCharsets.UTF_8).trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("bd58d6b5-5019-4d3c-a601-3b05fcc88191"))
    }

    @Test
    fun habitFilesAreStableAcrossInputOrderAndRoundTripAllStates() {
        val bundle = sampleHabitBackup()
        val first = OpenBackupCodec.encode(bundle)
        val second = OpenBackupCodec.encode(
            bundle.copy(
                habits = bundle.habits.reversed(),
                habitCheckIns = bundle.habitCheckIns.reversed(),
            ),
        )

        assertEquals(first.habitsJson.toList(), second.habitsJson.toList())
        assertEquals(first.habitCheckInsNdjson.toList(), second.habitCheckInsNdjson.toList())
        assertEquals(bundle.normalized(), OpenBackupCodec.decode(first.asMap()))
    }

    @Test
    fun emptyHabitFilesRemainPresentInNewBackups() {
        val files = OpenBackupCodec.encode(sampleBackup())

        assertEquals("[]", files.habitsJson.toString(StandardCharsets.UTF_8))
        assertEquals(0, files.habitCheckInsNdjson.size)
        assertTrue(HABITS_FILE in files.asMap())
        assertTrue(HABIT_CHECK_INS_FILE in files.asMap())
    }

    @Test
    fun checkInCompletedRequiresAnActualJsonBoolean() {
        listOf("\"true\"", "1", "null").forEach { invalid ->
            val files = OpenBackupCodec.encode(sampleHabitBackup()).asMap().toMutableMap()
            files[HABIT_CHECK_INS_FILE] = files.getValue(HABIT_CHECK_INS_FILE)
                .toString(StandardCharsets.UTF_8)
                .replace("\"completed\":true", "\"completed\":$invalid")
                .toByteArray(StandardCharsets.UTF_8)

            assertThrows(BackupFormatException::class.java) { OpenBackupCodec.decode(files) }
        }
    }

    @Test
    fun habitSortOrderCannotOverflowIntoAValidInt() {
        val files = OpenBackupCodec.encode(sampleHabitBackup()).asMap().toMutableMap()
        files[HABITS_FILE] = files.getValue(HABITS_FILE).toString(StandardCharsets.UTF_8)
            .replace("\"sortOrder\":1", "\"sortOrder\":4294967297")
            .toByteArray(StandardCharsets.UTF_8)

        assertThrows(BackupFormatException::class.java) { OpenBackupCodec.decode(files) }
    }
}
