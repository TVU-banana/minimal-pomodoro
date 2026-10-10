package com.akira.clock.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupMergerTest {
    @Test
    fun duplicateIdsKeepNewestUpdatedRecord() {
        val existing = sampleBackup().normalized()
        val oldSession = existing.sessions.first()
        val incoming = existing.copy(
            sessions = listOf(
                oldSession.copy(
                    actualFocusMs = oldSession.actualFocusMs + 1,
                    updatedAtUtcMs = oldSession.updatedAtUtcMs + 1,
                ),
            ),
        )

        val merged = BackupMerger.merge(existing, incoming)

        assertEquals(
            oldSession.actualFocusMs + 1,
            merged.sessions.first { it.id == oldSession.id }.actualFocusMs,
        )
    }

    @Test
    fun dailyReflectionKeepsTheNewestRevision() {
        val existing = sampleBackup().normalized()
        val reflection = existing.reflections.single()
        val incoming = existing.copy(
            reflections = listOf(
                reflection.copy(text = "更新后的收获", updatedAtUtcMs = reflection.updatedAtUtcMs + 1),
            ),
        )

        val merged = BackupMerger.merge(existing, incoming)

        assertEquals("更新后的收获", merged.reflections.single().text)
    }

    @Test
    fun newerLocalUndoWinsOverAnOlderCompletedBackup() {
        val existing = sampleHabitBackup().normalized()
        val undo = existing.habitCheckIns.single { !it.completed }
        val incoming = existing.copy(
            habitCheckIns = listOf(
                undo.copy(
                    completed = true, completedCount = 1,
                    completedAtUtcMs = undo.updatedAtUtcMs - 1,
                    updatedAtUtcMs = undo.updatedAtUtcMs - 1,
                ),
            ),
        )

        val merged = BackupMerger.merge(existing, incoming)

        assertEquals(existing.habitCheckIns, merged.habitCheckIns)
        assertEquals(existing.sessions, merged.sessions)
        assertEquals(existing.categories, merged.categories)
    }

    @Test
    fun newerImportedUndoReplacesACompletedLocalRow() {
        val incoming = sampleHabitBackup().normalized()
        val undo = incoming.habitCheckIns.single { !it.completed }
        val existing = incoming.copy(
            habitCheckIns = listOf(
                undo.copy(
                    completed = true, completedCount = 1,
                    completedAtUtcMs = undo.updatedAtUtcMs - 1,
                    updatedAtUtcMs = undo.updatedAtUtcMs - 1,
                ),
            ),
        )

        val merged = BackupMerger.merge(existing, incoming)

        assertEquals(incoming.habitCheckIns, merged.habitCheckIns)
    }

    @Test
    fun newerHabitDefinitionWinsAndEqualRevisionsKeepTheLocalValue() {
        val existing = sampleHabitBackup().normalized()
        val habit = existing.habits.first()
        val sameRevision = existing.copy(habits = listOf(habit.copy(name = "远端名称")))
        val newerRevision = existing.copy(
            habits = listOf(habit.copy(name = "更新名称", updatedAtUtcMs = habit.updatedAtUtcMs + 1)),
        )

        assertEquals(habit, BackupMerger.merge(existing, sameRevision).habits.first())
        assertEquals("更新名称", BackupMerger.merge(existing, newerRevision).habits.first().name)
    }

    @Test
    fun equalCheckInRevisionKeepsTheLocalUndoAndMergeIsIdempotent() {
        val existing = sampleHabitBackup().normalized()
        val undo = existing.habitCheckIns.single { !it.completed }
        val incoming = existing.copy(
            habitCheckIns = listOf(
                undo.copy(
                    completed = true,
                    completedCount = 1,
                    completedAtUtcMs = undo.updatedAtUtcMs,
                ),
            ),
        )

        val merged = BackupMerger.merge(existing, incoming)

        assertEquals(existing, merged)
        assertEquals(merged, BackupMerger.merge(merged, incoming))
    }

    @Test
    fun legacyImportDoesNotRemoveLocalHabitData() {
        val existing = sampleHabitBackup().normalized()

        val merged = BackupMerger.merge(existing, sampleBackup())

        assertEquals(existing.habits, merged.habits)
        assertEquals(existing.habitCheckIns, merged.habitCheckIns)
    }

    @Test
    fun conflictingCreationMetadataRejectsTheMergeEvenWithANewerRevision() {
        val existing = sampleHabitBackup().normalized()
        val habit = existing.habits.first()
        val incompatible = listOf(
            habit.copy(createdLocalDateIso = "2026-07-14"),
            habit.copy(createdAtUtcMs = habit.createdAtUtcMs + 1),
        )

        incompatible.forEach { value ->
            val incoming = existing.copy(
                habits = listOf(value.copy(updatedAtUtcMs = value.createdAtUtcMs + 10)),
            )
            assertThrows(BackupFormatException::class.java) {
                BackupMerger.merge(existing, incoming)
            }
        }
    }
}
