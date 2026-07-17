package com.akira.clock.timer

sealed interface TimerStage {
    data object Idle : TimerStage
    data object Warmup : TimerStage
    data object Focus : TimerStage
    data object Paused : TimerStage
    data object Completed : TimerStage
    data object Aborted : TimerStage
}
