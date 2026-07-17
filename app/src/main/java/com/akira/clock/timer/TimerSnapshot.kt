package com.akira.clock.timer

/** Durable domain snapshot from which all countdown values can be reconstructed. */
internal data class TimerSnapshot(
    val sessionId: String,
    val stage: TimerStage,
    val totalDurationMs: Long,
    val warmupDurationMs: Long,
    val accumulatedWarmupMs: Long,
    val accumulatedFocusMs: Long,
    val phaseStartedElapsedMs: Long?,
    val phaseDeadlineElapsedMs: Long?,
    val phaseDeadlineUtcMs: Long?,
    val updatedAtUtcMs: Long,
) {
    init {
        require(totalDurationMs > 0)
        require(warmupDurationMs >= 0)
        require(warmupDurationMs < totalDurationMs)
    }
}
