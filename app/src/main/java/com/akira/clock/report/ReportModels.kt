package com.akira.clock.report

import com.akira.clock.data.SessionEntity
import com.akira.clock.data.SessionPhase
import com.akira.clock.data.SessionSegmentEntity
import com.akira.clock.data.SessionStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

enum class ReportPeriod { MONTH, QUARTER, YEAR }

data class TimeRange(val fromUtcMs: Long, val toUtcMs: Long)

data class ReportRow(
    val categoryId: String,
    val categoryName: String,
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

fun reportRange(
    period: ReportPeriod,
    anchor: LocalDate,
    zoneId: ZoneId,
): TimeRange {
    val startDate = when (period) {
        ReportPeriod.MONTH -> anchor.withDayOfMonth(1)
        ReportPeriod.QUARTER -> {
            val firstMonth = ((anchor.monthValue - 1) / 3) * 3 + 1
            LocalDate.of(anchor.year, firstMonth, 1)
        }
        ReportPeriod.YEAR -> LocalDate.of(anchor.year, 1, 1)
    }
    val endDate = when (period) {
        ReportPeriod.MONTH -> startDate.plusMonths(1)
        ReportPeriod.QUARTER -> startDate.plusMonths(3)
        ReportPeriod.YEAR -> startDate.plusYears(1)
    }
    return TimeRange(
        fromUtcMs = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
        toUtcMs = endDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
    )
}

fun aggregateReport(
    sessions: List<SessionEntity>,
    segments: List<SessionSegmentEntity>,
    range: TimeRange,
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
        .filter { it.status == SessionStatus.COMPLETED.name || it.status == SessionStatus.ABORTED.name }
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
        val overlapStart = maxOf(segment.startedAtUtcMs, range.fromUtcMs)
        val overlapEnd = minOf(end, range.toUtcMs)
        val duration = (overlapEnd - overlapStart).coerceAtLeast(0L)
        if (duration == 0L) return@forEach
        val row = rows.getOrPut(session.categoryId) { MutableRow(session.categoryNameSnapshot) }
        if (segment.phase == SessionPhase.WARMUP.name) row.warmupMs += duration else row.focusMs += duration
    }

    return rows.map { (categoryId, row) ->
        ReportRow(
            categoryId = categoryId,
            categoryName = row.name,
            warmupMs = row.warmupMs,
            focusMs = row.focusMs,
            completedCount = row.completed,
            abortedCount = row.aborted,
        )
    }.sortedByDescending { it.focusMs }
}

fun currentDate(zoneId: ZoneId, nowUtcMs: Long): LocalDate =
    ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowUtcMs), zoneId).toLocalDate()
