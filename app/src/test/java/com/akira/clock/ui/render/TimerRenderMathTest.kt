package com.akira.clock.ui.render

import com.akira.clock.timer.TimerStage
import org.junit.Assert.assertEquals
import org.junit.Test

class TimerRenderMathTest {
    @Test
    fun `remaining time rounds up so timer never shows zero early`() {
        assertEquals("00:01", TimerRenderMath.formatRemaining(1L))
        assertEquals("01:00", TimerRenderMath.formatRemaining(59_001L))
        assertEquals("01:02:03", TimerRenderMath.formatRemaining(3_723_000L))
    }

    @Test
    fun `progress values exposed to renderers are bounded`() {
        val state = TimerUiState(
            stage = TimerStage.Focus,
            taskName = "学习",
            categoryName = "成长",
            remainingMs = 1L,
            phaseDurationMs = 1L,
            totalProgress = 1.2f,
            phaseProgress = -0.1f,
            paused = false,
        )
        assertEquals(1f, state.boundedTotalProgress)
        assertEquals(0f, state.boundedPhaseProgress)
    }

    @Test
    fun `accessibility description includes semantic stage and remaining time`() {
        val state = TimerUiState(
            stage = TimerStage.Warmup,
            taskName = "学习",
            categoryName = "成长",
            remainingMs = 60_000L,
            phaseDurationMs = 900_000L,
            totalProgress = 0f,
            phaseProgress = 0f,
            paused = false,
        )
        assertEquals("学习，成长，启动阶段，剩余 01:00", TimerRenderMath.accessibilityDescription(state))
    }
}
