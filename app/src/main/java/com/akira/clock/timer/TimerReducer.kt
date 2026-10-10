package com.akira.clock.timer

/**
 * Calculates the next timer snapshot for [command].
 *
 * The reducer performs no Android, database, alarm, or notification work. Time and identifiers are
 * injected so a transition is deterministic under test.
 *
 * @throws IllegalArgumentException when [command] is invalid for the current state.
 */
internal fun reduce(
    current: TimerSnapshot?,
    command: TimerCommand,
    clock: ClockSource,
    identifiers: IdentifierSource,
): TimerSnapshot? = when (command) {
    is TimerCommand.Start -> {
        require(current == null || current.stage == TimerStage.Idle) {
            "A timer can only start from Idle"
        }
        startSession(command, clock, identifiers)
    }

    TimerCommand.Pause ->
        requireNotNull(current).pauseAt(clock.elapsedRealtimeMs(), clock.utcNowMs())

    TimerCommand.Resume ->
        requireNotNull(current).resumeAt(clock.elapsedRealtimeMs(), clock.utcNowMs())

    TimerCommand.PhaseDeadlineReached ->
        requireNotNull(current).advancePhase(clock)

    TimerCommand.Abort ->
        requireNotNull(current).abortAt(clock.elapsedRealtimeMs(), clock.utcNowMs())
}

fun remainingMs(
    deadlineElapsedMs: Long,
    nowElapsedMs: Long,
): Long = (deadlineElapsedMs - nowElapsedMs).coerceAtLeast(0L)

private fun startSession(
    command: TimerCommand.Start,
    clock: ClockSource,
    identifiers: IdentifierSource,
): TimerSnapshot {
    require(command.presetId.isNotBlank()) { "presetId must not be blank" }
    if (command.mode == TimerMode.COUNTDOWN) {
        require(command.totalDurationMs > 0L) { "totalDurationMs must be positive" }
        require(command.warmupDurationMs >= 0L) { "warmupDurationMs must not be negative" }
        require(command.warmupDurationMs < command.totalDurationMs) {
            "warmupDurationMs must be shorter than totalDurationMs"
        }
    } else {
        require(command.totalDurationMs == 0L && command.warmupDurationMs == 0L) {
            "A stopwatch cannot have a planned duration or warmup"
        }
    }

    val nowElapsedMs = clock.elapsedRealtimeMs()
    val nowUtcMs = clock.utcNowMs()
    val stage = if (command.warmupDurationMs > 0L) TimerStage.Warmup else TimerStage.Focus
    val phaseDurationMs = if (stage == TimerStage.Warmup) {
        command.warmupDurationMs
    } else {
        command.totalDurationMs
    }

    return TimerSnapshot(
        sessionId = identifiers.newId(),
        stage = stage,
        totalDurationMs = command.totalDurationMs,
        warmupDurationMs = command.warmupDurationMs,
        mode = command.mode,
        accumulatedWarmupMs = 0L,
        accumulatedFocusMs = 0L,
        phaseStartedElapsedMs = nowElapsedMs,
        phaseDeadlineElapsedMs = if (command.mode == TimerMode.COUNTDOWN) {
            nowElapsedMs + phaseDurationMs
        } else {
            null
        },
        phaseDeadlineUtcMs = if (command.mode == TimerMode.COUNTDOWN) {
            nowUtcMs + phaseDurationMs
        } else {
            null
        },
        updatedAtUtcMs = nowUtcMs,
    )
}

private fun TimerSnapshot.pauseAt(
    nowElapsedMs: Long,
    nowUtcMs: Long,
): TimerSnapshot {
    require(stage == TimerStage.Warmup || stage == TimerStage.Focus) {
        "Only an active timer can be paused"
    }

    val elapsedInPhaseMs = elapsedInCurrentPhase(nowElapsedMs)
    return when (stage) {
        TimerStage.Warmup -> copy(
            stage = TimerStage.Paused,
            accumulatedWarmupMs =
                (accumulatedWarmupMs + elapsedInPhaseMs).coerceAtMost(warmupDurationMs),
            phaseStartedElapsedMs = null,
            phaseDeadlineElapsedMs = null,
            phaseDeadlineUtcMs = null,
            updatedAtUtcMs = nowUtcMs,
        )

        TimerStage.Focus -> copy(
            stage = TimerStage.Paused,
            accumulatedFocusMs =
            if (mode == TimerMode.COUNTDOWN) {
                (accumulatedFocusMs + elapsedInPhaseMs).coerceAtMost(focusDurationMs)
            } else {
                accumulatedFocusMs + elapsedInPhaseMs
            },
            phaseStartedElapsedMs = null,
            phaseDeadlineElapsedMs = null,
            phaseDeadlineUtcMs = null,
            updatedAtUtcMs = nowUtcMs,
        )

        else -> error("Unreachable")
    }
}

private fun TimerSnapshot.resumeAt(
    nowElapsedMs: Long,
    nowUtcMs: Long,
): TimerSnapshot {
    require(stage == TimerStage.Paused) { "Only a paused timer can be resumed" }

    val resumedStage = if (accumulatedWarmupMs < warmupDurationMs) {
        TimerStage.Warmup
    } else {
        TimerStage.Focus
    }
    val phaseRemainingMs = when (resumedStage) {
        TimerStage.Warmup -> warmupDurationMs - accumulatedWarmupMs
        TimerStage.Focus -> focusDurationMs - accumulatedFocusMs
        else -> error("Unreachable")
    }.coerceAtLeast(0L)

    return copy(
        stage = resumedStage,
        phaseStartedElapsedMs = nowElapsedMs,
        phaseDeadlineElapsedMs = if (mode == TimerMode.COUNTDOWN) {
            nowElapsedMs + phaseRemainingMs
        } else {
            null
        },
        phaseDeadlineUtcMs = if (mode == TimerMode.COUNTDOWN) {
            nowUtcMs + phaseRemainingMs
        } else {
            null
        },
        updatedAtUtcMs = nowUtcMs,
    )
}

private fun TimerSnapshot.advancePhase(clock: ClockSource): TimerSnapshot {
    require(
        mode == TimerMode.COUNTDOWN &&
            (stage == TimerStage.Warmup || stage == TimerStage.Focus)
    ) {
        "Only an active timer can advance its phase"
    }

    val nowElapsedMs = clock.elapsedRealtimeMs()
    val nowUtcMs = clock.utcNowMs()
    val currentDeadlineElapsedMs = requireNotNull(phaseDeadlineElapsedMs)

    // A stale duplicate alarm must not advance a newly started phase.
    if (nowElapsedMs < currentDeadlineElapsedMs) return this

    return when (stage) {
        TimerStage.Warmup -> {
            val nextPhaseDurationMs = focusDurationMs
            val focusStartedElapsedMs = currentDeadlineElapsedMs
            val focusDeadlineElapsedMs = focusStartedElapsedMs + nextPhaseDurationMs
            val focusDeadlineUtcMs = requireNotNull(phaseDeadlineUtcMs) + nextPhaseDurationMs
            copy(
                stage = TimerStage.Focus,
                accumulatedWarmupMs = warmupDurationMs,
                phaseStartedElapsedMs = focusStartedElapsedMs,
                phaseDeadlineElapsedMs = focusDeadlineElapsedMs,
                phaseDeadlineUtcMs = focusDeadlineUtcMs,
                updatedAtUtcMs = nowUtcMs,
            )
        }

        TimerStage.Focus -> copy(
            stage = TimerStage.Completed,
            accumulatedFocusMs = focusDurationMs,
            phaseStartedElapsedMs = null,
            phaseDeadlineElapsedMs = null,
            phaseDeadlineUtcMs = null,
            updatedAtUtcMs = nowUtcMs,
        )

        else -> error("Unreachable")
    }
}

private fun TimerSnapshot.abortAt(
    nowElapsedMs: Long,
    nowUtcMs: Long,
): TimerSnapshot {
    require(
        stage == TimerStage.Warmup ||
            stage == TimerStage.Focus ||
            stage == TimerStage.Paused,
    ) { "Only an active or paused timer can be aborted" }

    val elapsedInPhaseMs = if (stage == TimerStage.Paused) {
        0L
    } else {
        elapsedInCurrentPhase(nowElapsedMs)
    }
    return copy(
        stage = TimerStage.Aborted,
        accumulatedWarmupMs = if (stage == TimerStage.Warmup) {
            (accumulatedWarmupMs + elapsedInPhaseMs).coerceAtMost(warmupDurationMs)
        } else {
            accumulatedWarmupMs
        },
        accumulatedFocusMs = if (stage == TimerStage.Focus) {
            if (mode == TimerMode.COUNTDOWN) {
                (accumulatedFocusMs + elapsedInPhaseMs).coerceAtMost(focusDurationMs)
            } else {
                accumulatedFocusMs + elapsedInPhaseMs
            }
        } else {
            accumulatedFocusMs
        },
        phaseStartedElapsedMs = null,
        phaseDeadlineElapsedMs = null,
        phaseDeadlineUtcMs = null,
        updatedAtUtcMs = nowUtcMs,
    )
}

private fun TimerSnapshot.elapsedInCurrentPhase(nowElapsedMs: Long): Long {
    val startedElapsedMs = requireNotNull(phaseStartedElapsedMs)
    val deadlineElapsedMs = phaseDeadlineElapsedMs
    val effectiveNowMs = deadlineElapsedMs?.let(nowElapsedMs::coerceAtMost) ?: nowElapsedMs
    return (effectiveNowMs - startedElapsedMs).coerceAtLeast(0L)
}

private val TimerSnapshot.focusDurationMs: Long
    get() = totalDurationMs - warmupDurationMs
