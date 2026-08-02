package com.akira.clock.report

import com.akira.clock.data.ClockDatabase
import com.akira.clock.data.SessionEntity
import com.akira.clock.data.SessionStatus
import java.time.LocalDate
import java.time.ZoneId

/** Aggregate report plus the terminal session list for the selected range. */
internal data class ReportData(
    val rows: List<ReportRow>,
    val sessions: List<SessionEntity>,
    val categoryColors: Map<String, Long>,
)

/** Loads report segments from Room and delegates deterministic aggregation to the domain layer. */
internal class ReportService(private val database: ClockDatabase) {
    suspend fun load(
        period: ReportPeriod,
        anchor: LocalDate,
        zoneId: ZoneId,
    ): ReportData {
        val range = reportRange(period, anchor, zoneId)
        val categoryColors = database.categoryDao().allForExport().associate { it.id to it.colorArgb }
        val allSessions = database.sessionDao().allForExport()
        val rows = aggregateReport(
            sessions = allSessions,
            segments = database.sessionSegmentDao().overlapping(range.fromUtcMs, range.toUtcMs),
            range = range,
            categoryColors = categoryColors,
        )
        // Session-level detail (the former history list), newest first.
        val sessions = allSessions
            .filter { it.startedAtUtcMs in range.fromUtcMs until range.toUtcMs }
            .filter { it.status == SessionStatus.COMPLETED.name || it.status == SessionStatus.ABORTED.name }
            .sortedByDescending { it.startedAtUtcMs }
        return ReportData(rows, sessions, categoryColors)
    }
}
