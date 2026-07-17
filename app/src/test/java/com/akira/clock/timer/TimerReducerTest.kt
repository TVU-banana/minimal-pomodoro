package com.akira.clock.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class TimerReducerTest {
    @Test
    fun `60 minute task contains 15 minute warmup and 45 minute focus`() {
        val clock = FakeClock(elapsedMs = 0L, utcMs = 1_000_000L)

        var state = reduce(
            null,
            TimerCommand.Start(
                presetId = "preset-1",
                totalDurationMs = TimerDefaults.TOTAL_DURATION_MS,
                warmupDurationMs = TimerDefaults.WARMUP_DURATION_MS,
            ),
            clock,
        )!!

        assertEquals(TimerStage.Warmup, state.stage)
        assertEquals(15 * MINUTE_MS, state.phaseDeadlineElapsedMs)

        clock.advanceBy(15 * MINUTE_MS)
        state = reduce(state, TimerCommand.PhaseDeadlineReached, clock)!!

        assertEquals(TimerStage.Focus, state.stage)
        assertEquals(
            45 * MINUTE_MS,
            state.phaseDeadlineElapsedMs!! - clock.elapsedRealtimeMs(),
        )
        assertEquals(15 * MINUTE_MS, state.accumulatedWarmupMs)
    }

    @Test
    fun `zero warmup starts directly in focus for the full total duration`() {
        val clock = FakeClock(elapsedMs = 5_000L, utcMs = 10_000L)

        val state = reduce(
            null,
            TimerCommand.Start("preset-1", 60 * MINUTE_MS, 0L),
            clock,
        )!!

        assertEquals(TimerStage.Focus, state.stage)
        assertEquals(60 * MINUTE_MS, state.phaseDeadlineElapsedMs!! - clock.elapsedRealtimeMs())
    }

    @Test
    fun `paused duration is excluded from actual focus`() {
        val clock = FakeClock()
        var state = reduce(
            null,
            TimerCommand.Start("preset-1", 45 * MINUTE_MS, 0L),
            clock,
        )!!

        clock.advanceBy(10 * MINUTE_MS)
        state = reduce(state, TimerCommand.Pause, clock)!!
        assertEquals(TimerStage.Paused, state.stage)
        assertEquals(10 * MINUTE_MS, state.accumulatedFocusMs)

        clock.advanceBy(20 * MINUTE_MS)
        state = reduce(state, TimerCommand.Resume, clock)!!
        assertEquals(TimerStage.Focus, state.stage)
        assertEquals(35 * MINUTE_MS, state.phaseDeadlineElapsedMs!! - clock.elapsedRealtimeMs())

        clock.advanceBy(35 * MINUTE_MS)
        state = reduce(state, TimerCommand.PhaseDeadlineReached, clock)!!

        assertEquals(45 * MINUTE_MS, state.accumulatedFocusMs)
        assertEquals(TimerStage.Completed, state.stage)
    }

    @Test
    fun `warmup pause resumes warmup with only uninvested time remaining`() {
        val clock = FakeClock()
        var state = reduce(
            null,
            TimerCommand.Start("preset-1", 60 * MINUTE_MS, 15 * MINUTE_MS),
            clock,
        )!!

        clock.advanceBy(5 * MINUTE_MS)
        state = reduce(state, TimerCommand.Pause, clock)!!
        clock.advanceBy(30 * MINUTE_MS)
        state = reduce(state, TimerCommand.Resume, clock)!!

        assertEquals(TimerStage.Warmup, state.stage)
        assertEquals(5 * MINUTE_MS, state.accumulatedWarmupMs)
        assertEquals(10 * MINUTE_MS, state.phaseDeadlineElapsedMs!! - clock.elapsedRealtimeMs())
    }

    @Test
    fun `early or duplicate deadline command is idempotent`() {
        val clock = FakeClock()
        var state = reduce(
            null,
            TimerCommand.Start("preset-1", 60 * MINUTE_MS, 15 * MINUTE_MS),
            clock,
        )!!

        assertSame(state, reduce(state, TimerCommand.PhaseDeadlineReached, clock))

        clock.advanceBy(15 * MINUTE_MS)
        state = reduce(state, TimerCommand.PhaseDeadlineReached, clock)!!
        val focusState = state

        assertSame(focusState, reduce(focusState, TimerCommand.PhaseDeadlineReached, clock))
        assertEquals(TimerStage.Focus, state.stage)
    }

    @Test
    fun `abort records active time and makes timer terminal`() {
        val clock = FakeClock()
        var state = reduce(
            null,
            TimerCommand.Start("preset-1", 60 * MINUTE_MS, 15 * MINUTE_MS),
            clock,
        )!!

        clock.advanceBy(4 * MINUTE_MS)
        state = reduce(state, TimerCommand.Abort, clock)!!

        assertEquals(TimerStage.Aborted, state.stage)
        assertEquals(4 * MINUTE_MS, state.accumulatedWarmupMs)
        assertThrows(IllegalArgumentException::class.java) {
            reduce(state, TimerCommand.Resume, clock)
        }
    }

    @Test
    fun `terminal timer rejects every command including start`() {
        val clock = FakeClock()
        var state = reduce(
            null,
            TimerCommand.Start("preset-1", MINUTE_MS, 0L),
            clock,
        )!!
        clock.advanceBy(MINUTE_MS)
        state = reduce(state, TimerCommand.PhaseDeadlineReached, clock)!!

        assertEquals(TimerStage.Completed, state.stage)
        assertThrows(IllegalArgumentException::class.java) {
            reduce(state, TimerCommand.Start("preset-2", MINUTE_MS, 0L), clock)
        }
        assertThrows(IllegalArgumentException::class.java) {
            reduce(state, TimerCommand.Abort, clock)
        }
    }

    @Test
    fun `remaining time never becomes negative`() {
        assertEquals(500L, remainingMs(deadlineElapsedMs = 1_000L, nowElapsedMs = 500L))
        assertEquals(0L, remainingMs(deadlineElapsedMs = 1_000L, nowElapsedMs = 1_500L))
    }

    @Test
    fun `start creates a distinct session id and validates duration contract`() {
        val clock = FakeClock()
        val first = reduce(
            null,
            TimerCommand.Start("preset-1", MINUTE_MS, 0L),
            clock,
        )!!
        val second = reduce(
            null,
            TimerCommand.Start("preset-1", MINUTE_MS, 0L),
            clock,
        )!!

        assertNotEquals(first.sessionId, second.sessionId)
        assertThrows(IllegalArgumentException::class.java) {
            reduce(null, TimerCommand.Start("preset-1", MINUTE_MS, MINUTE_MS), clock)
        }
    }

    private class FakeClock(
        private var elapsedMs: Long = 0L,
        private var utcMs: Long = 0L,
    ) : ClockSource {
        override fun elapsedRealtimeMs(): Long = elapsedMs

        override fun utcNowMs(): Long = utcMs

        fun advanceBy(durationMs: Long) {
            elapsedMs += durationMs
            utcMs += durationMs
        }
    }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
