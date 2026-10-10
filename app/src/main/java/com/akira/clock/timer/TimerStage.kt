package com.akira.clock.timer

/** Mutually exclusive lifecycle stages of one timer session. */
internal sealed interface TimerStage {
    /** No session is active or loaded; UI displays initial duration choices. */
    data object Idle : TimerStage

    /** Optional preparatory countdown phase running before formal focus. */
    data object Warmup : TimerStage

    /** Active formal focus phase accumulating productive duration. */
    data object Focus : TimerStage

    /** Active countdown paused; monotonic deadline cleared, preserving elapsed progress. */
    data object Paused : TimerStage

    /** Terminal stage reached when planned focus duration completes successfully. */
    data object Completed : TimerStage

    /** Terminal stage reached when the user or system ends a session before completion. */
    data object Aborted : TimerStage
}
