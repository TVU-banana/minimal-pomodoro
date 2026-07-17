package com.akira.clock.ui.render

interface TimerRenderer {
    fun bind(state: TimerUiState)
    fun startRendering()
    fun stopRendering()
}
