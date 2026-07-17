package com.akira.clock.export

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupMergerTest {
    @Test
    fun `duplicate ids keep newest updated record`() {
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

        assertEquals(oldSession.actualFocusMs + 1, merged.sessions.first { it.id == oldSession.id }.actualFocusMs)
    }
}
