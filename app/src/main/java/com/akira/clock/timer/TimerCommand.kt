package com.akira.clock.timer

sealed interface TimerCommand {
    data class Start(
        val presetId: String,
        val totalDurationMs: Long,
        val warmupDurationMs: Long,
    ) : TimerCommand

    data object Pause : TimerCommand
    data object Resume : TimerCommand
    data object Abort : TimerCommand
    data object PhaseDeadlineReached : TimerCommand
}
