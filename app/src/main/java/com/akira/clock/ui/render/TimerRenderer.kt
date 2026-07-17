package com.akira.clock.ui.render

/** Lifecycle contract shared by every timer visualization. */
internal interface TimerRenderer {
    fun bind(state: TimerUiState)
    fun startRendering()
    fun stopRendering()
}
