package com.akira.clock.timer

/** Complete set of commands accepted by the timer state machine. */
internal sealed interface TimerCommand {
    /** Transitions the state machine from Idle to Warmup (if configured) or Focus. */
    data class Start(
        val presetId: String,
        val totalDurationMs: Long,
        val warmupDurationMs: Long,
        val mode: TimerMode = TimerMode.COUNTDOWN,
    ) : TimerCommand {
        companion object {
            /**
             * Presets specify formal focus separately from warmup. Domain totals retain their
             * wall-time meaning so existing persisted checkpoints resume without reinterpretation.
             */
            fun forTimeBlock(
                presetId: String,
                focusDurationMs: Long,
                warmupDurationMs: Long,
                mode: TimerMode = TimerMode.COUNTDOWN,
            ): Start {
                require(warmupDurationMs >= 0L)
                require(
                    (mode == TimerMode.COUNTDOWN && focusDurationMs > 0L) ||
                        (mode == TimerMode.STOPWATCH && focusDurationMs == 0L &&
                            warmupDurationMs == 0L),
                )
                return Start(
                    presetId,
                    Math.addExact(focusDurationMs, warmupDurationMs),
                    warmupDurationMs,
                    mode,
                )
            }
        }
    }

    /** Transitions an active Warmup or Focus countdown to Paused, freezing elapsed progress. */
    data object Pause : TimerCommand

    /** Restores an active countdown from Paused back to Warmup or Focus. */
    data object Resume : TimerCommand

    /** Terminates an active or paused timer session early, recording accumulated durations. */
    data object Abort : TimerCommand

    /** Dispatched by the alarm scheduler when a monotonic phase deadline is reached. */
    data object PhaseDeadlineReached : TimerCommand
}
