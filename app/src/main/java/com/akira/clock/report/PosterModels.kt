package com.akira.clock.report

import com.akira.clock.data.SessionEntity
import com.akira.clock.data.SessionStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Compact, renderer-independent data for one exported focus poster. */
internal data class FocusPosterData(
    val period: ReportPeriod,
    val rangeStart: LocalDate,
    val rangeEndExclusive: LocalDate,
    val totalFocusMs: Long,
    val values: List<Long>,
)

/** Converts terminal session records into the small data set needed by a poster. */
internal object FocusPosterDataFactory {
    fun create(
        period: ReportPeriod,
        sessions: List<SessionEntity>,
        anchor: LocalDate,
        zoneId: ZoneId,
    ): FocusPosterData {
        val range = when (period) {
            ReportPeriod.DAY -> anchor to anchor.plusDays(1)
            ReportPeriod.WEEK -> {
                val start = anchor.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                start to start.plusWeeks(1)
            }
            ReportPeriod.MONTH -> anchor.withDayOfMonth(1) to anchor.withDayOfMonth(1).plusMonths(1)
            ReportPeriod.YEAR -> anchor.withDayOfYear(1) to anchor.withDayOfYear(1).plusYears(1)
            ReportPeriod.ALL_TIME -> {
                val first = sessions.minOfOrNull { it.startedAtUtcMs }?.let {
                    Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate()
                } ?: anchor
                first to anchor.plusDays(1)
            }
        }
        val start = range.first
        val end = range.second
        val terminal = sessions.filter {
            it.status == SessionStatus.COMPLETED.name || it.status == SessionStatus.ABORTED.name
        }
        val values = when (period) {
            ReportPeriod.DAY -> List(24) { hour ->
                terminal.filter { session ->
                    val time = Instant.ofEpochMilli(session.startedAtUtcMs).atZone(zoneId)
                    time.toLocalDate() == start && time.hour == hour
                }.sumOf(SessionEntity::actualFocusMs)
            }
            else -> List((end.toEpochDay() - start.toEpochDay()).toInt()) { index ->
                val date = start.plusDays(index.toLong())
                terminal.filter { session ->
                    Instant.ofEpochMilli(session.startedAtUtcMs).atZone(zoneId).toLocalDate() == date
                }.sumOf(SessionEntity::actualFocusMs)
            }
        }
        return FocusPosterData(period, start, end, values.sum(), values)
    }
}
