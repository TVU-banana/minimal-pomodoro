package com.akira.clock.band

import com.akira.clock.timer.TimerMode
import com.akira.clock.timer.TimerSnapshot
import com.akira.clock.timer.TimerStage

internal enum class BandAction { STATE, PRESETS, START, PAUSE, RESUME, ABORT }

internal enum class BandCode { OK, STALE, INVALID_STATE, NOT_FOUND, ALARM_UNAVAILABLE, CONFLICT }

/** A process-scoped optimistic command; tokens must never be refreshed for automatic retries. */
internal data class BandRequest(
    val id: String,
    val action: BandAction,
    val expectedToken: String? = null,
    val presetId: String? = null,
    val sessionId: String? = null,
    val offset: Int = 0,
) {
    init {
        require(id.isNotBlank() && id.length <= 128)
        listOf(expectedToken, presetId, sessionId).filterNotNull().forEach {
            require(it.isNotBlank() && it.length <= 128)
        }
        require(offset >= 0)
        if (action !in listOf(BandAction.STATE, BandAction.PRESETS)) {
            require(expectedToken != null)
            if (action == BandAction.START) {
                require(presetId != null)
            } else require(sessionId != null)
        }
    }
}

/** Device-independent display projection. No phone monotonic timestamp crosses the wire. */
internal data class BandState(
    val token: String,
    val sessionId: String?,
    val stage: String,
    val remainingMs: Long,
    val running: Boolean,
    val taskName: String,
)

internal data class BandPreset(val id: String, val name: String, val durationMs: Long)

internal data class BandReply(
    val id: String,
    val code: BandCode,
    val state: BandState,
    val presets: List<BandPreset> = emptyList(),
    val nextOffset: Int? = null,
)

/** Projects paused phases without reinterpreting warmup as focus or consulting the wall clock. */
internal fun bandState(
    snapshot: TimerSnapshot?,
    token: String,
    nowElapsedMs: Long,
    taskName: String,
): BandState {
    val stage = when (snapshot?.stage) {
        null, TimerStage.Idle -> "IDLE"
        TimerStage.Warmup -> "WARMUP"
        TimerStage.Focus -> "FOCUS"
        TimerStage.Paused -> "PAUSED"
        TimerStage.Completed -> "COMPLETED"
        TimerStage.Aborted -> "ABORTED"
    }
    val running = stage == "WARMUP" || stage == "FOCUS"
    val remaining = when {
        snapshot == null || snapshot.mode != TimerMode.COUNTDOWN -> 0L
        running -> (requireNotNull(snapshot.phaseDeadlineElapsedMs) - nowElapsedMs)
            .coerceAtLeast(0L)
        stage == "PAUSED" -> if (snapshot.accumulatedWarmupMs < snapshot.warmupDurationMs) {
            snapshot.warmupDurationMs - snapshot.accumulatedWarmupMs
        } else {
            snapshot.totalDurationMs - snapshot.warmupDurationMs - snapshot.accumulatedFocusMs
        }.coerceAtLeast(0L)
        else -> 0L
    }
    return BandState(token, snapshot?.sessionId, stage, remaining, running, taskName)
}
