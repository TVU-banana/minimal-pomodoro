package com.akira.clock.timer

/**
 * Durable countdown state. The total includes warmup for persisted-checkpoint compatibility;
 * formal focus is total minus warmup. Preset totals are converted at the command boundary.
 */
internal data class TimerSnapshot(
    val sessionId: String,
    val stage: TimerStage,
    val totalDurationMs: Long,
    val warmupDurationMs: Long,
    val mode: TimerMode,
    val accumulatedWarmupMs: Long,
    val accumulatedFocusMs: Long,
    val phaseStartedElapsedMs: Long?,
    val phaseDeadlineElapsedMs: Long?,
    val phaseDeadlineUtcMs: Long?,
    val updatedAtUtcMs: Long,
) {
    init {
        require(totalDurationMs >= 0)
        require(warmupDurationMs >= 0)
        require(
            (mode == TimerMode.COUNTDOWN && warmupDurationMs < totalDurationMs) ||
                (mode == TimerMode.STOPWATCH && totalDurationMs == 0L && warmupDurationMs == 0L),
        )
    }
}
