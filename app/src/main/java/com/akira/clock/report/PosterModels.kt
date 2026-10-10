package com.akira.clock.report

import com.akira.clock.data.SessionEntity
import com.akira.clock.data.SessionPhase
import com.akira.clock.data.SessionSegmentEntity
import com.akira.clock.data.SessionStatus
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Compact, renderer-independent data for one exported focus poster. */
internal data class FocusPosterData(
    val period: ReportPeriod,
    val rangeStart: LocalDate,
    val rangeEndExclusive: LocalDate,
    val totalDurationMs: Long,
    val values: List<Long>,
)

/** Converts terminal session records into the small data set needed by a poster. */
internal object FocusPosterDataFactory {
    fun create(
        period: ReportPeriod,
        sessions: List<SessionEntity>,
        anchor: LocalDate,
        zoneId: ZoneId,
        segments: List<SessionSegmentEntity> = emptyList(),
    ): FocusPosterData {
        val range = when (period) {
            ReportPeriod.DAY -> anchor to anchor.plusDays(1)
            ReportPeriod.WEEK -> {
                val start = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                start to start.plusWeeks(1)
            }
            ReportPeriod.YEAR -> anchor.withDayOfYear(1) to anchor.withDayOfYear(1).plusYears(1)
        }
        val start = range.first
        val end = range.second
        val terminal = sessions.filter {
            it.status == SessionStatus.COMPLETED.name || it.status == SessionStatus.ABORTED.name
        }
        val values = MutableList(
            if (period == ReportPeriod.DAY) 24 else (end.toEpochDay() - start.toEpochDay()).toInt(),
        ) { 0L }
        val focusBySession = segments.filter { it.phase == SessionPhase.FOCUS.name }
            .groupBy { it.sessionId }
        val fromMs = start.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val toMs = end.atStartOfDay(zoneId).toInstant().toEpochMilli()
        terminal.forEach { session ->
            val focusSegments = focusBySession[session.id].orEmpty()
            // Legacy imports without segments retain their actual focus; warmup earns no credit.
            val intervals = if (focusSegments.isEmpty()) {
                val focusStart = session.startedAtUtcMs + session.actualWarmupMs
                listOf(focusStart to focusStart + session.actualFocusMs)
            } else {
                focusSegments.mapNotNull { segment ->
                    segment.endedAtUtcMs?.let { segment.startedAtUtcMs to it }
                }
            }
            intervals.forEach { (from, to) ->
                var cursor = maxOf(from, fromMs)
                val clippedEnd = minOf(to, toMs)
                while (cursor < clippedEnd) {
                    val local = Instant.ofEpochMilli(cursor).atZone(zoneId)
                    val boundary = if (period == ReportPeriod.DAY) {
                        local.truncatedTo(ChronoUnit.HOURS).plusHours(1)
                    } else {
                        local.toLocalDate().plusDays(1).atStartOfDay(zoneId)
                    }.toInstant().toEpochMilli()
                    val bucketEnd = minOf(boundary, clippedEnd)
                    val index = if (period == ReportPeriod.DAY) local.hour else {
                        (local.toLocalDate().toEpochDay() - start.toEpochDay()).toInt()
                    }
                    values[index] += bucketEnd - cursor
                    cursor = bucketEnd
                }
            }
        }
        return FocusPosterData(period, start, end, values.sum(), values)
    }
}
