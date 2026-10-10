package com.akira.clock.export

import com.akira.clock.data.SessionEntity
import java.time.Instant

internal object SessionCsvExporter {
    private val header = listOf(
        "id", "presetId", "categoryId", "categoryName", "taskName",
        "plannedTotalMs", "plannedWarmupMs", "actualWarmupMs", "actualFocusMs",
        "startedAt", "endedAt", "timezoneId", "status", "updatedAt",
    )

    /**
     * RFC 4180, UTF-8 when the returned String is encoded, CRLF records,
     * deterministic ordering.
     */
    fun export(sessions: List<SessionEntity>): String = buildString {
        appendRecord(header)
        val sortedSessions = sessions.sortedWith(
            compareBy(SessionEntity::startedAtUtcMs, SessionEntity::id)
        )
        sortedSessions.forEach { session ->
            appendRecord(
                listOf(
                    session.id,
                    session.presetId.orEmpty(),
                    session.categoryId,
                    session.categoryNameSnapshot,
                    session.taskNameSnapshot,
                    session.plannedTotalMs.toString(),
                    session.plannedWarmupMs.toString(),
                    session.actualWarmupMs.toString(),
                    session.actualFocusMs.toString(),
                    Instant.ofEpochMilli(session.startedAtUtcMs).toString(),
                    session.endedAtUtcMs?.let { Instant.ofEpochMilli(it).toString() }.orEmpty(),
                    session.timezoneId,
                    session.status,
                    Instant.ofEpochMilli(session.updatedAtUtcMs).toString(),
                ),
            )
        }
    }

    private fun StringBuilder.appendRecord(values: List<String>) {
        values.forEachIndexed { index, value ->
            if (index > 0) append(',')
            append(escape(value))
        }
        append("\r\n")
    }

    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
}
