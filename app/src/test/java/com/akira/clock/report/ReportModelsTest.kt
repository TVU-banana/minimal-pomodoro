package com.akira.clock.report

import com.akira.clock.data.SessionEntity
import com.akira.clock.data.SessionPhase
import com.akira.clock.data.SessionSegmentEntity
import com.akira.clock.data.SessionStatus
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportModelsTest {
    @Test
    fun weekRangeStartsOnMonday() {
        // 2026-07-17 is a Friday; the ISO week begins on the preceding Monday.
        val range = reportRange(
            ReportPeriod.WEEK,
            LocalDate.of(2026, 7, 17),
            ZoneId.of("Asia/Shanghai"),
        )
        val start = java.time.Instant.ofEpochMilli(range.fromUtcMs)
            .atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
        assertEquals(DayOfWeek.MONDAY, start.dayOfWeek)
        assertEquals(LocalDate.of(2026, 7, 13), start)
    }

    @Test
    fun yearRangeStartsOnTheFirstDayOfTheYear() {
        val zone = ZoneId.of("UTC")
        val range = reportRange(ReportPeriod.YEAR, LocalDate.of(2026, 7, 17), zone)
        val start = java.time.Instant.ofEpochMilli(range.fromUtcMs).atZone(zone).toLocalDate()

        assertEquals(LocalDate.of(2026, 1, 1), start)
    }

    @Test
    fun segmentCrossingReportBoundaryIsClippedExactly() {
        val range = TimeRange(1_000L, 2_000L)
        val session = session("s1")
        val rows = aggregateReport(
            sessions = listOf(session),
            segments = listOf(
                SessionSegmentEntity("g1", "s1", SessionPhase.FOCUS.name, 500L, 1_500L),
                SessionSegmentEntity("g2", "s1", SessionPhase.WARMUP.name, 1_800L, 2_500L),
            ),
            range = range,
        )
        assertEquals(500L, rows.single().focusMs)
        assertEquals(200L, rows.single().warmupMs)
    }

    @Test
    fun runningSessionIsExcludedUntilTerminal() {
        val running = session("s1").copy(
            status = SessionStatus.RUNNING.name,
            endedAtUtcMs = null,
        )
        val rows = aggregateReport(
            sessions = listOf(running),
            segments = listOf(
                SessionSegmentEntity("g1", "s1", SessionPhase.FOCUS.name, 1_100L, null),
            ),
            range = TimeRange(1_000L, 2_000L),
        )
        assertEquals(emptyList<ReportRow>(), rows)
    }

    @Test
    fun aggregateReportAttachesCategoryColorWhenKnown() {
        val rows = aggregateReport(
            sessions = listOf(session("s1")),
            segments = listOf(
                SessionSegmentEntity("g1", "s1", SessionPhase.FOCUS.name, 0L, 1_000L),
            ),
            range = TimeRange(0L, Long.MAX_VALUE),
            categoryColors = mapOf("c1" to 0xFF28C840L),
        )
        assertEquals(0xFF28C840L, rows.single().colorArgb)
    }

    private fun session(id: String) = SessionEntity(
        id = id,
        presetId = null,
        categoryId = "c1",
        categoryNameSnapshot = "学习",
        taskNameSnapshot = "阅读",
        plannedTotalMs = 3_600_000L,
        plannedWarmupMs = 900_000L,
        actualWarmupMs = 0L,
        actualFocusMs = 0L,
        startedAtUtcMs = 1_100L,
        endedAtUtcMs = 1_900L,
        timezoneId = "Asia/Shanghai",
        status = SessionStatus.COMPLETED.name,
        createdAtUtcMs = 1_100L,
        updatedAtUtcMs = 1_900L,
    )
}
