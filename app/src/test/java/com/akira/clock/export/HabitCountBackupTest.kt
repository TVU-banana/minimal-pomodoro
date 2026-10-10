package com.akira.clock.export

import com.akira.clock.data.HabitTargetEntity
import com.akira.clock.habit.HabitPolicy
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class HabitCountBackupTest {
    private val source = sampleHabitBackup()
    private val habit = source.habits.first { it.archivedLocalDateIso == null }
    private val date = LocalDate.of(2026, 7, 14)
    private val target = HabitTargetEntity(habit.id, habit.createdLocalDateIso, 3, 100)
    private val partial = source.habitCheckIns.first {
        it.habitId == habit.id && it.localDateIso == date.toString()
    }.copy(completed = false, completedAtUtcMs = null, completedCount = 2)
    private val bundle = source.copy(
        habitTargets = listOf(target), habitCheckIns = listOf(partial),
    )

    @Test
    fun archiveRoundTripPreservesPartialProgressAndTargetHistory() {
        val bytes = ClockBackupArchive.writeToBytes(bundle, "test", Instant.EPOCH)
        assertEquals(bundle.normalized(), ClockBackupArchive.read(bytes).bundle)
    }

    @Test
    fun newerUndoAndTargetRevisionMergeIndependentlyWithoutLosingCounts() {
        val undo = partial.copy(completedCount = 1, updatedAtUtcMs = partial.updatedAtUtcMs + 1)
        val changed = target.copy(targetCount = 4, updatedAtUtcMs = 101)
        val incoming = bundle.copy(habitTargets = listOf(changed), habitCheckIns = listOf(undo))
        val merged = BackupMerger.merge(bundle, incoming)
        assertEquals(1, HabitPolicy.countFor(habit.id, date, merged.habitCheckIns))
        assertEquals(4, HabitPolicy.targetFor(habit.id, date, merged.habitTargets))
        assertFalse(merged.habitCheckIns.single().completed)
        assertEquals(merged, BackupMerger.merge(merged, bundle))
    }

    @Test
    fun malformedCountsTargetsDuplicateKeysAndOrphansAreRejected() {
        for (count in listOf(-1, 100)) {
            assertThrows(BackupFormatException::class.java) {
                BackupValidator.validate(bundle.copy(habitCheckIns = listOf(
                    partial.copy(completedCount = count),
                )))
            }
        }
        for (value in listOf(
            target.copy(targetCount = 0), target.copy(targetCount = 100),
            target.copy(habitId = "missing"), target.copy(effectiveLocalDateIso = "2026-07-12"),
            target.copy(updatedAtUtcMs = -1),
        )) {
            assertThrows(BackupFormatException::class.java) {
                BackupValidator.validate(bundle.copy(habitTargets = listOf(value)))
            }
        }
        assertThrows(BackupFormatException::class.java) {
            BackupValidator.validate(bundle.copy(habitTargets = listOf(target, target)))
        }
    }
}
