package com.akira.clock.ui

import com.akira.clock.data.SessionEntity
import com.akira.clock.data.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class TodayProgressTest {
    @Test
    fun completedCountdownUsesTheSameCreditAsTodayDetails() {
        val result = calculate(listOf(session(SessionStatus.COMPLETED, total = 60, focus = 45)))
        assertEquals(45L, result.creditedMs)
    }

    @Test
    fun stopwatchAndAbortedCreditExcludeWarmupWithoutAThreshold() {
        val rows = listOf(
            session(SessionStatus.COMPLETED, total = 0, focus = 20),
            session(SessionStatus.ABORTED, warmup = 15, focus = 14),
            session(SessionStatus.ABORTED, warmup = 15, focus = 25),
            session(SessionStatus.RUNNING, focus = 40),
        )
        assertEquals(59L, calculate(rows).creditedMs)
    }

    @Test
    fun excludesOtherDaysAndPresetsAndUsesHalfOpenDayRange() {
        val base = session(SessionStatus.COMPLETED, total = 65, warmup = 5, focus = 60)
        val rows = listOf(
            base.copy(startedAtUtcMs = 99),
            base.copy(startedAtUtcMs = 100),
            base.copy(startedAtUtcMs = 200),
            base.copy(presetId = "archived"),
        )
        assertEquals(60L, calculate(rows).creditedMs)
    }

    @Test
    fun progressCapsAtFullAndSupportsMissingGoals() {
        assertEquals(1f, TodayProgress(200, 100).fraction, 0f)
        assertEquals(0.5f, TodayProgress(50, 100).fraction, 0f)
        assertEquals(0f, TodayProgress(50, 0).fraction, 0f)
    }

    private fun calculate(rows: List<SessionEntity>): TodayProgress =
        todayProgress(rows, setOf("preset"), 180, 100, 200)

    private fun session(
        status: SessionStatus,
        total: Long = 60,
        warmup: Long = 0,
        focus: Long = 0,
    ) = SessionEntity(
        id = "session-${status.name}-$focus",
        presetId = "preset",
        categoryId = "tag",
        categoryNameSnapshot = "tag",
        plannedTotalMs = total,
        plannedWarmupMs = warmup,
        actualWarmupMs = warmup,
        actualFocusMs = focus,
        startedAtUtcMs = 150,
        endedAtUtcMs = 190,
        timezoneId = "UTC",
        status = status.name,
        createdAtUtcMs = 150,
        updatedAtUtcMs = 190,
    )
}
