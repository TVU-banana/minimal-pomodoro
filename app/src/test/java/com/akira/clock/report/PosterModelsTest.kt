package com.akira.clock.report

import com.akira.clock.data.SessionEntity
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
        assertEquals(90 * 60_000L, data.totalFocusMs)
    }

    private fun session(id: String, startedAt: Long, focusMs: Long, status: String = SessionStatus.COMPLETED.name) =
        SessionEntity(
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
