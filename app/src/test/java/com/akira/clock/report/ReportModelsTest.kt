package com.akira.clock.report

import com.akira.clock.data.SessionEntity
import com.akira.clock.data.SessionPhase
import com.akira.clock.data.SessionSegmentEntity
import com.akira.clock.data.SessionStatus
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportModelsTest {
    @Test
    fun quarterRangeStartsOnQuarterBoundary() {
        val range = reportRange(
            ReportPeriod.QUARTER,
            LocalDate.of(2026, 7, 17),
            ZoneId.of("Asia/Shanghai"),
        )
        assertEquals(
            LocalDate.of(2026, 7, 1),
            java.time.Instant.ofEpochMilli(range.fromUtcMs).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(),
        )
        assertEquals(
            LocalDate.of(2026, 10, 1),
            java.time.Instant.ofEpochMilli(range.toUtcMs).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(),
        )
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
