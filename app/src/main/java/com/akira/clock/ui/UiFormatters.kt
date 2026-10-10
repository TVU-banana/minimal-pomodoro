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
    return if (totalMinutes < 60) {
        context.getString(R.string.duration_short_minutes, totalMinutes)
    } else if (totalMinutes % 60 == 0L) {
        context.getString(R.string.duration_short_hours, totalMinutes / 60)
    } else {
        context.getString(
            R.string.duration_short_hours_minutes,
            totalMinutes / 60,
            totalMinutes % 60,
        )
    }
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
