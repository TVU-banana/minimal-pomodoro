package com.akira.clock.ui.render

import com.akira.clock.timer.TimerStage

/** Immutable presentation state consumed by all timer renderers. */
internal data class TimerUiState(
    val stage: TimerStage,
    val taskName: String,
    val categoryName: String,
    val remainingMs: Long,
    val phaseDurationMs: Long,
    val totalProgress: Float,
    val phaseProgress: Float,
    val paused: Boolean,
) {
    init {
        require(remainingMs >= 0L)
        require(phaseDurationMs >= 0L)
        require(totalProgress.isFinite())
        require(phaseProgress.isFinite())
    }

    val boundedTotalProgress: Float
        get() = totalProgress.coerceIn(0f, 1f)

    val boundedPhaseProgress: Float
        get() = phaseProgress.coerceIn(0f, 1f)
}
