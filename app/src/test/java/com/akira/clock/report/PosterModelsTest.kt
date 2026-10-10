package com.akira.clock.report

import com.akira.clock.data.SessionEntity
import com.akira.clock.data.SessionPhase
import com.akira.clock.data.SessionSegmentEntity
import com.akira.clock.data.SessionStatus
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class PosterModelsTest {
    @Test
    fun weeklyPosterUsesMondayFirstDailyValuesAndExcludesRunningSessions() {
        val zone = ZoneId.of("Asia/Shanghai")
        val monday = LocalDate.of(2026, 8, 3)
        val mondayMs = monday.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val tuesdayMs = monday.plusDays(1).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val data = FocusPosterDataFactory.create(
            ReportPeriod.WEEK,
            listOf(
                session("monday", mondayMs, 60 * 60_000L),
                session("tuesday", tuesdayMs, 30 * 60_000L),
                session("running", mondayMs, 4 * 60 * 60_000L, SessionStatus.RUNNING.name),
            ),
            monday.plusDays(2),
            zone,
        )

        assertEquals(monday, data.rangeStart)
        assertEquals(listOf(60L, 30L, 0L, 0L, 0L, 0L, 0L), data.values.map { it / 60_000 })
        assertEquals(90 * 60_000L, data.totalDurationMs)
    }

    @Test
    fun posterExcludesWarmupDurationFromTheFocusTotal() {
        val zone = ZoneId.of("Asia/Shanghai")
        val day = LocalDate.of(2026, 8, 3)
        val startedAt = day.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val session = session("completed", startedAt, 15 * 60_000L).copy(
            actualWarmupMs = 15 * 60_000L,
        )

        val data = FocusPosterDataFactory.create(ReportPeriod.DAY, listOf(session), day, zone)

        assertEquals(15 * 60_000L, data.totalDurationMs)
        assertEquals(15 * 60_000L, data.values[9])
    }

    @Test
    fun distributionSplitsFormalSegmentsAcrossHoursAndExcludesWarmupAndPauses() {
        val zone = ZoneId.of("UTC")
        val day = LocalDate.of(2026, 10, 7)
        val start = day.atTime(9, 50).atZone(zone).toInstant().toEpochMilli()
        val minute = 60_000L
        val row = session("focus", start, 60 * minute).copy(actualWarmupMs = 5 * minute)
        val segments = listOf(
            SessionSegmentEntity(
                "warm", row.id, SessionPhase.WARMUP.name, start, start + 5 * minute,
            ),
            SessionSegmentEntity("a", row.id, SessionPhase.FOCUS.name,
                start + 5 * minute, start + 20 * minute),
            SessionSegmentEntity("b", row.id, SessionPhase.FOCUS.name,
                start + 30 * minute, start + 75 * minute),
        )
        val data = FocusPosterDataFactory.create(ReportPeriod.DAY, listOf(row), day, zone, segments)
        assertEquals(24, data.values.size)
        assertEquals(60 * minute, data.totalDurationMs)
        assertEquals(5 * minute, data.values[9])
        assertEquals(50 * minute, data.values[10])
        assertEquals(5 * minute, data.values[11])
    }

    private fun session(
        id: String,
        startedAt: Long,
        focusMs: Long,
        status: String = SessionStatus.COMPLETED.name,
    ) = SessionEntity(
            id = id,
            presetId = null,
            categoryId = "category",
            categoryNameSnapshot = "学习",
            plannedTotalMs = focusMs,
            plannedWarmupMs = 0,
            actualWarmupMs = 0,
            actualFocusMs = focusMs,
            startedAtUtcMs = startedAt,
            endedAtUtcMs = startedAt + focusMs,
            timezoneId = "Asia/Shanghai",
            status = status,
            createdAtUtcMs = startedAt,
            updatedAtUtcMs = startedAt + focusMs,
        )
}
