package com.akira.clock.timer

/** Mutually exclusive lifecycle stages of one timer session. */
internal sealed interface TimerStage {
    data object Idle : TimerStage
    data object Warmup : TimerStage
    data object Focus : TimerStage
    data object Paused : TimerStage
    data object Completed : TimerStage
    data object Aborted : TimerStage
}
