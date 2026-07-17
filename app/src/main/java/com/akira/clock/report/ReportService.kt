package com.akira.clock.report

import com.akira.clock.data.ClockDatabase
import java.time.LocalDate
import java.time.ZoneId

class ReportService(private val database: ClockDatabase) {
    suspend fun load(
        period: ReportPeriod,
        anchor: LocalDate,
        zoneId: ZoneId,
    ): List<ReportRow> {
        val range = reportRange(period, anchor, zoneId)
        return aggregateReport(
            sessions = database.sessionDao().allForExport(),
            segments = database.sessionSegmentDao().overlapping(range.fromUtcMs, range.toUtcMs),
            range = range,
        )
    }
}

