package com.akira.clock.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCsvExporterTest {
    @Test
    fun `CSV uses RFC4180 escaping and CRLF`() {
        val session = sampleSession().copy(categoryNameSnapshot = "学习,\"专注\"\n第二行")

        val csv = SessionCsvExporter.export(listOf(session))

        assertTrue(csv.contains("\"学习,\"\"专注\"\"\n第二行\""))
        assertEquals(2, csv.windowed(2).count { it == "\r\n" })
        assertTrue(csv.endsWith("\r\n"))
    }

    @Test
    fun `CSV records are sorted by start and id`() {
        val backup = sampleBackup()
        val csv = SessionCsvExporter.export(backup.sessions)
        val first = backup.sessions.minBy { it.startedAtUtcMs }

        assertTrue(csv.indexOf(first.id) < csv.indexOf(backup.sessions.maxBy { it.startedAtUtcMs }.id))
    }
}
