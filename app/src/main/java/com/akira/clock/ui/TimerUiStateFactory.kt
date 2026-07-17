package com.akira.clock.ui

import com.akira.clock.timer.TimerSnapshot
import com.akira.clock.timer.TimerStage
import com.akira.clock.timer.remainingMs
import com.akira.clock.ui.render.TimerUiState

/** Maps a persisted timer snapshot to the immutable state consumed by every renderer. */
internal object TimerUiStateFactory {
    fun create(
        snapshot: TimerSnapshot,
        taskName: String,
        categoryName: String,
        nowElapsedMs: Long,
    ): TimerUiState {
        val activeRemaining = snapshot.phaseDeadlineElapsedMs?.let {
            remainingMs(it, nowElapsedMs)
        }
        val focusDuration = snapshot.totalDurationMs - snapshot.warmupDurationMs
        val isWarmupPhase = snapshot.stage == TimerStage.Warmup ||
            (snapshot.stage == TimerStage.Paused &&
                snapshot.accumulatedWarmupMs < snapshot.warmupDurationMs)
        val phaseDuration = if (isWarmupPhase) snapshot.warmupDurationMs else focusDuration
        val accumulated = if (isWarmupPhase) {
            snapshot.accumulatedWarmupMs
        } else {
            snapshot.accumulatedFocusMs
        }
        val pausedRemaining = (phaseDuration - accumulated).coerceAtLeast(0L)
        val remaining = activeRemaining ?: if (snapshot.stage == TimerStage.Paused) {
            pausedRemaining
        } else {
            0L
        }
        val elapsedInPhase = (phaseDuration - remaining).coerceIn(0L, phaseDuration)
        val currentActiveElapsed = snapshot.phaseStartedElapsedMs?.let {
            (nowElapsedMs - it)
                .coerceAtLeast(0L)
                .coerceAtMost(phaseDuration - accumulated)
        } ?: 0L
        val invested = snapshot.accumulatedWarmupMs +
            snapshot.accumulatedFocusMs +
            currentActiveElapsed

        return TimerUiState(
            stage = snapshot.stage,
            taskName = taskName,
            categoryName = categoryName,
            remainingMs = remaining,
            phaseDurationMs = phaseDuration,
            totalProgress = invested.toFloat() / snapshot.totalDurationMs,
            phaseProgress = if (phaseDuration == 0L) {
                1f
            } else {
                elapsedInPhase.toFloat() / phaseDuration
            },
            paused = snapshot.stage == TimerStage.Paused,
        )
    }
}
