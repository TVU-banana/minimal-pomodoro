package com.akira.clock.ui

import android.content.Context
import com.akira.clock.R
import com.akira.clock.data.SessionEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Shared presentation formatters used by the timer, history, and reports surfaces. */

internal fun formatDuration(context: Context, durationMs: Long): String {
    val totalMinutes = durationMs / 60_000L
    return "${totalMinutes}min"
}

internal fun statusLabel(context: Context, status: String): String =
    context.getString(
        when (status) {
            "COMPLETED" -> R.string.session_status_completed
            "ABORTED" -> R.string.session_status_aborted
            "PAUSED" -> R.string.session_status_paused
            else -> R.string.session_status_running
        },
    )

internal fun formatSessionDate(session: SessionEntity): String =
    Instant.ofEpochMilli(session.startedAtUtcMs)
        .atZone(ZoneId.of(session.timezoneId))
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
