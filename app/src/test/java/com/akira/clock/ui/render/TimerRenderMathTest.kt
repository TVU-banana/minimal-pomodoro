package com.akira.clock.ui.render

import com.akira.clock.timer.TimerStage
import org.junit.Assert.assertEquals
import org.junit.Test

class TimerRenderMathTest {
    @Test
    fun remainingTimeRoundsUpWithoutShowingZeroEarly() {
        assertEquals("00:01", TimerRenderMath.formatRemaining(1L))
        assertEquals("01:00", TimerRenderMath.formatRemaining(59_001L))
        assertEquals("01:02:03", TimerRenderMath.formatRemaining(3_723_000L))
    }

    @Test
    fun rendererProgressValuesAreBounded() {
        val state = TimerUiState(
            stage = TimerStage.Focus,
            taskName = "Task",
            categoryName = "Category",
            remainingMs = 1L,
            phaseDurationMs = 1L,
            totalProgress = 1.2f,
            phaseProgress = -0.1f,
            paused = false,
        )
        assertEquals(1f, state.boundedTotalProgress)
        assertEquals(0f, state.boundedPhaseProgress)
    }

}
