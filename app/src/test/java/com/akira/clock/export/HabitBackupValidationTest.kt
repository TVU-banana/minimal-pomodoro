package com.akira.clock.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HabitBackupValidationTest {
    @Test
    fun invalidHabitIdsDatesNamesOrderingAndTimestampsAreRejected() {
        val source = sampleHabitBackup()
        val habit = source.habits.first()
        val invalid = listOf(
            habit.copy(id = "habit"),
            habit.copy(id = "1-2-3-4-5"),
            habit.copy(name = ""),
            habit.copy(name = " 阅读 "),
            habit.copy(name = "a".repeat(121)),
            habit.copy(note = " untrimmed "),
            habit.copy(note = "x".repeat(1001)),
            habit.copy(sortOrder = -1),
            habit.copy(createdLocalDateIso = "2026-02-29"),
            habit.copy(createdLocalDateIso = "2026-7-13"),
            habit.copy(archivedLocalDateIso = "2026-07-12"),
            habit.copy(archivedLocalDateIso = "2026-02-30"),
            habit.copy(createdAtUtcMs = -1),
            habit.copy(updatedAtUtcMs = habit.createdAtUtcMs - 1),
        )

        invalid.forEach { value ->
            assertThrows(BackupFormatException::class.java) {
                BackupValidator.validate(source.copy(habits = listOf(value)))
            }
        }
    }

    @Test
    fun notesRoundTripAndOlderBackupsDefaultToEmptyNotes() {
        val source = sampleHabitBackup()
        val habit = source.habits.first().copy(note = "十页\n睡前阅读")
        val bundle = source.copy(habits = source.habits.map {
            if (it.id == habit.id) habit else it
        })
        val files = OpenBackupCodec.encode(bundle).asMap()
        assertEquals(bundle.normalized(), OpenBackupCodec.decode(files))
        val legacy = files.toMutableMap()
        legacy[HABITS_FILE] = files.getValue(HABITS_FILE).decodeToString()
            .replace("\"note\":\"十页\\n睡前阅读\",", "").encodeToByteArray()
        assertEquals("", OpenBackupCodec.decode(legacy).habits.first { it.id == habit.id }.note)
    }

    @Test
    fun duplicateHabitIdsAndCompositeCheckInKeysAreRejected() {
        val source = sampleHabitBackup()
        val habit = source.habits.first()
        val checkIn = source.habitCheckIns.first()

        assertThrows(BackupFormatException::class.java) {
            BackupValidator.validate(source.copy(habits = listOf(habit, habit)))
        }
        assertThrows(BackupFormatException::class.java) {
            BackupValidator.validate(source.copy(habitCheckIns = listOf(checkIn, checkIn)))
        }
    }

    @Test
    fun invalidCheckInReferencesDatesAndCompletionTimestampsAreRejected() {
        val source = sampleHabitBackup()
        val checkIn = source.habitCheckIns.first { it.completed }
        val invalid = listOf(
            checkIn.copy(habitId = "90289410-c0d8-4d10-b480-b1b575355c03"),
            checkIn.copy(localDateIso = "2026-02-29"),
            checkIn.copy(localDateIso = "2026-07-14T00:00:00Z"),
            checkIn.copy(localDateIso = "2026-7-14"),
            checkIn.copy(completedAtUtcMs = null),
            checkIn.copy(completedAtUtcMs = -1),
            checkIn.copy(completedAtUtcMs = checkIn.updatedAtUtcMs + 1),
            checkIn.copy(completed = false),
            checkIn.copy(updatedAtUtcMs = -1),
        )

        invalid.forEach { value ->
            assertThrows(BackupFormatException::class.java) {
                BackupValidator.validate(source.copy(habitCheckIns = listOf(value)))
            }
        }
    }

    @Test
    fun leapDaysSameDayArchiveAndRetainedArchiveDayRecordsAreValid() {
        val source = sampleHabitBackup()
        val habit = source.habits.first().copy(
            createdLocalDateIso = "2024-02-29",
            archivedLocalDateIso = "2024-02-29",
        )
        val checkIn = source.habitCheckIns.first { it.completed }.copy(
            habitId = habit.id,
            localDateIso = "2024-02-29",
        )
        val bundle = source.copy(habits = listOf(habit), habitCheckIns = listOf(checkIn))

        assertEquals(bundle, BackupValidator.validate(bundle))
    }

    @Test
    fun shiftedLocalDatesAndRewoundCompletionClockRemainExportable() {
        val source = sampleHabitBackup()
        val habit = source.habits.first()
        val completionTime = habit.createdAtUtcMs - 86_400_000
        val checkIn = source.habitCheckIns.first { it.completed }.copy(
            habitId = habit.id,
            localDateIso = "2099-12-31",
            completedAtUtcMs = completionTime,
            updatedAtUtcMs = completionTime + 1,
        )
        val bundle = source.copy(habits = listOf(habit), habitCheckIns = listOf(checkIn))

        val restored = OpenBackupCodec.decode(OpenBackupCodec.encode(bundle).asMap())

        assertEquals(bundle.normalized(), restored)
    }
}
