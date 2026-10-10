package com.akira.clock.report

import com.akira.clock.data.SessionEntity
import com.akira.clock.data.SessionPhase
import com.akira.clock.data.SessionSegmentEntity
import com.akira.clock.data.SessionStatus
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

internal enum class ReportPeriod { YEAR, WEEK, DAY }

internal data class TimeRange(val fromUtcMs: Long, val toUtcMs: Long)

internal data class ReportRow(
    val categoryId: String,
    val categoryName: String,
    val colorArgb: Long,
    val warmupMs: Long,
    val focusMs: Long,
    val completedCount: Int,
    val abortedCount: Int,
) {
    val investedMs: Long get() = warmupMs + focusMs
    val completionRate: Float
        get() {
            val total = completedCount + abortedCount
            return if (total == 0) 0f else completedCount.toFloat() / total
        }
}

/**
 * Resolves a [ReportPeriod] to a UTC half-open range anchored at [anchor] in [zoneId].
 *
 * WEEK starts on Monday (ISO 8601).
 */
internal fun reportRange(period: ReportPeriod, anchor: LocalDate, zoneId: ZoneId): TimeRange =
    when (period) {
        ReportPeriod.YEAR -> rangeOf(anchor.withDayOfYear(1), zoneId) { plusYears(1) }
        ReportPeriod.WEEK -> {
            val weekStart = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            rangeOf(weekStart, zoneId) { plusWeeks(1) }
        }
        ReportPeriod.DAY -> rangeOf(anchor, zoneId) { plusDays(1) }
    }

private fun rangeOf(
    start: LocalDate,
    zoneId: ZoneId,
    endStep: LocalDate.() -> LocalDate,
): TimeRange =
    TimeRange(
        fromUtcMs = start.atStartOfDay(zoneId).toInstant().toEpochMilli(),
        toUtcMs = start.endStep().atStartOfDay(zoneId).toInstant().toEpochMilli(),
    )

internal fun aggregateReport(
    sessions: List<SessionEntity>,
    segments: List<SessionSegmentEntity>,
    range: TimeRange,
    categoryColors: Map<String, Long> = emptyMap(),
): List<ReportRow> {
    val sessionById = sessions.associateBy { it.id }
    data class MutableRow(
        var name: String,
        var warmupMs: Long = 0,
        var focusMs: Long = 0,
        var completed: Int = 0,
        var aborted: Int = 0,
    )
    val rows = linkedMapOf<String, MutableRow>()

    sessions.asSequence()
        .filter { it.startedAtUtcMs in range.fromUtcMs until range.toUtcMs }
        .filter {
            it.status == SessionStatus.COMPLETED.name || it.status == SessionStatus.ABORTED.name
        }
        .forEach { session ->
            val row = rows.getOrPut(session.categoryId) { MutableRow(session.categoryNameSnapshot) }
            if (session.status == SessionStatus.COMPLETED.name) row.completed++ else row.aborted++
        }

    segments.forEach { segment ->
        val session = sessionById[segment.sessionId] ?: return@forEach
        if (session.status != SessionStatus.COMPLETED.name &&
            session.status != SessionStatus.ABORTED.name
        ) return@forEach
        val end = segment.endedAtUtcMs ?: range.toUtcMs
        // Clip each segment to the window [fromUtcMs, toUtcMs) so segments crossing
        // boundaries are attributed accurately without double-counting.
        val overlapStart = maxOf(segment.startedAtUtcMs, range.fromUtcMs)
        val overlapEnd = minOf(end, range.toUtcMs)
        val duration = (overlapEnd - overlapStart).coerceAtLeast(0L)
        if (duration == 0L) return@forEach
        val row = rows.getOrPut(session.categoryId) { MutableRow(session.categoryNameSnapshot) }
        if (segment.phase == SessionPhase.WARMUP.name) {
            row.warmupMs += duration
        } else {
            row.focusMs += duration
        }
    }

    return rows.map { (categoryId, row) ->
        ReportRow(
            categoryId = categoryId,
            categoryName = row.name,
            colorArgb = categoryColors[categoryId] ?: DEFAULT_TAG_COLOR,
            warmupMs = row.warmupMs,
            focusMs = row.focusMs,
            completedCount = row.completed,
            abortedCount = row.aborted,
        )
    }.sortedByDescending { it.focusMs }
}

internal fun currentDate(zoneId: ZoneId, nowUtcMs: Long): LocalDate =
    ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowUtcMs), zoneId).toLocalDate()

/** Fallback tag color when a category no longer exists at render time. */
internal const val DEFAULT_TAG_COLOR: Long = 0xFFD95D39L
