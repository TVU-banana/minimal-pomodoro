package com.akira.clock.alarm

import androidx.room.withTransaction
import com.akira.clock.band.BandPreset
import com.akira.clock.data.CategoryEntity
import com.akira.clock.data.ClockDatabase
import com.akira.clock.data.SessionEntity
import com.akira.clock.data.SessionSegmentEntity
import com.akira.clock.data.SessionStatus
import com.akira.clock.data.TaskPresetEntity
import com.akira.clock.data.TimerCheckpointEntity
import com.akira.clock.timer.ClockSource
import com.akira.clock.timer.IdentifierSource
import com.akira.clock.timer.TimerMode
import com.akira.clock.timer.TimerSnapshot
import com.akira.clock.timer.TimerStage
import java.time.ZoneId

/** Data required to create a session without exposing DAOs to the coordinator. */
internal data class TimerStartContext(
    val preset: TaskPresetEntity,
    val category: CategoryEntity,
)

/**
 * Owns the Room transaction boundary for timer transitions.
 *
 * A session row, its activity segment, and the singleton checkpoint are committed together. System
 * effects such as AlarmManager and notifications remain outside this class because they cannot join
 * a SQLite transaction.
 */
internal class TimerTransitionStore(
    private val database: ClockDatabase,
    private val clock: ClockSource,
    private val bootCount: () -> Int,
    private val identifiers: IdentifierSource,
) {
    /** Lists only resolvable countdown presets, with a bounded development page size. */
    suspend fun bandPresets(offset: Int): Pair<List<BandPreset>, Int?> {
        val presets = database.taskPresetDao().bandPage(offset)
        val page = presets.take(4).map {
            BandPreset(it.id, it.name.take(80), it.totalDurationMs)
        }
        val next = if (presets.size > 4 && offset <= Int.MAX_VALUE - 4) offset + 4 else null
        return page to next
    }

    suspend fun isBandPreset(id: String): Boolean {
        val preset = database.taskPresetDao().get(id) ?: return false
        val category = database.categoryDao().get(preset.categoryId) ?: return false
        return preset.timerMode == TimerMode.COUNTDOWN.name && category.archivedAtUtcMs == null
    }

    suspend fun checkpoint(): TimerCheckpointEntity? = database.timerCheckpointDao().get()

    suspend fun loadStartContext(presetId: String): TimerStartContext {
        val preset = requireNotNull(database.taskPresetDao().get(presetId)) {
            "Unknown preset: $presetId"
        }
        val category = requireNotNull(database.categoryDao().get(preset.categoryId)) {
            "Preset category no longer exists: ${preset.categoryId}"
        }
        return TimerStartContext(preset, category)
    }

    fun createSession(snapshot: TimerSnapshot, context: TimerStartContext): SessionEntity =
        SessionEntity(
            id = snapshot.sessionId,
            presetId = context.preset.id,
            categoryId = context.category.id,
            categoryNameSnapshot = context.category.name,
            taskNameSnapshot = context.preset.name,
            plannedTotalMs = snapshot.totalDurationMs,
            plannedWarmupMs = snapshot.warmupDurationMs,
            actualWarmupMs = 0L,
            actualFocusMs = 0L,
            startedAtUtcMs = snapshot.updatedAtUtcMs,
            endedAtUtcMs = null,
            timezoneId = ZoneId.systemDefault().id,
            status = SessionStatus.RUNNING.name,
            createdAtUtcMs = snapshot.updatedAtUtcMs,
            updatedAtUtcMs = snapshot.updatedAtUtcMs,
        )

    suspend fun session(sessionId: String): SessionEntity =
        requireNotNull(database.sessionDao().get(sessionId)) {
            "Session no longer exists: $sessionId"
        }

    /**
     * Reconstructs a snapshot and rebases elapsedRealtime values after a device reboot.
     *
     * Wall-clock time is used only to rebuild the monotonic deadline. Normal countdown calculations
     * continue to use elapsedRealtime so manual clock changes cannot restart a phase.
     */
    suspend fun restore(
        checkpoint: TimerCheckpointEntity,
        forceRebaseElapsedClock: Boolean,
    ): TimerSnapshot {
        var restored = checkpoint.toSnapshot()
        val currentBootCount = bootCount()
        if (restored.stage.isActive &&
            (forceRebaseElapsedClock || checkpoint.bootCount != currentBootCount)
        ) {
            restored = restored.rebaseElapsedClock(clock)
            val session = session(restored.sessionId)
            database.withTransaction {
                database.timerCheckpointDao().upsert(
                    restored.toCheckpoint(currentBootCount, checkpoint.previousStage),
                )
                database.sessionDao().upsert(
                    session.copy(updatedAtUtcMs = clock.utcNowMs()),
                )
            }
        }
        return restored
    }

    /** Commits all database changes produced by one state transition atomically. */
    suspend fun persist(
        previous: TimerSnapshot?,
        next: TimerSnapshot,
        session: SessionEntity,
    ) {
        database.withTransaction {
            if (next.stage.isTerminal && shouldDiscard(session)) {
                // A warmup-only attempt has no formal focus to report. This only discards the
                // currently ending attempt; existing terminal history is never rewritten.
                database.sessionSegmentDao().deleteForSession(session.id)
                database.sessionDao().delete(session.id)
                database.timerCheckpointDao().delete()
            } else {
                // Insert the session first because activity segments reference it through
                // a foreign key.
                database.sessionDao().upsert(session)
                updateSegments(previous, next)
                if (next.stage.isTerminal) {
                    database.timerCheckpointDao().delete()
                } else {
                    val previousStage = when {
                        next.stage == TimerStage.Paused -> previous?.stage?.databaseName
                        previous?.stage == TimerStage.Paused -> null
                        else -> database.timerCheckpointDao().get()?.previousStage
                    }
                    database.timerCheckpointDao().upsert(
                        next.toCheckpoint(bootCount(), previousStage),
                    )
                }
            }
        }
    }

    /** Only attempts with positive formal focus become terminal records. */
    private fun shouldDiscard(session: SessionEntity): Boolean =
        session.actualFocusMs <= 0L

    fun updateSession(
        previous: TimerSnapshot,
        next: TimerSnapshot,
        existing: SessionEntity,
        abortReason: String? = null,
    ): SessionEntity =
        existing.copy(
            actualWarmupMs = next.accumulatedWarmupMs,
            actualFocusMs = next.accumulatedFocusMs,
            endedAtUtcMs = when (next.stage) {
                TimerStage.Completed -> previous.phaseDeadlineUtcMs ?: next.updatedAtUtcMs
                TimerStage.Aborted -> next.updatedAtUtcMs
                else -> null
            },
            status = when (next.stage) {
                TimerStage.Paused -> SessionStatus.PAUSED.name
                TimerStage.Completed -> SessionStatus.COMPLETED.name
                TimerStage.Aborted -> SessionStatus.ABORTED.name
                else -> SessionStatus.RUNNING.name
            },
            // The reason is only meaningful for an abort; preserve any prior value otherwise.
            abortReason = if (next.stage == TimerStage.Aborted) {
                abortReason
            } else {
                existing.abortReason
            },
            updatedAtUtcMs = next.updatedAtUtcMs,
        )

    private suspend fun updateSegments(previous: TimerSnapshot?, next: TimerSnapshot) {
        val segmentDao = database.sessionSegmentDao()
        val eventUtcMs = when {
            previous?.stage == TimerStage.Warmup && next.stage == TimerStage.Focus ->
                previous.phaseDeadlineUtcMs ?: next.updatedAtUtcMs
            previous?.stage == TimerStage.Focus && next.stage == TimerStage.Completed ->
                previous.phaseDeadlineUtcMs ?: next.updatedAtUtcMs
            else -> next.updatedAtUtcMs
        }
        val shouldClose =
            previous != null && previous.stage.isActive && previous.stage != next.stage ||
                next.stage == TimerStage.Paused || next.stage.isTerminal
        if (shouldClose) {
            segmentDao.openForSession(next.sessionId)?.let { open ->
                segmentDao.upsert(
                    open.copy(endedAtUtcMs = eventUtcMs.coerceAtLeast(open.startedAtUtcMs)),
                )
            }
        }
        val shouldOpen = next.stage.isActive && (previous == null || previous.stage != next.stage)
        if (shouldOpen) {
            segmentDao.upsert(
                SessionSegmentEntity(
                    id = identifiers.newId(),
                    sessionId = next.sessionId,
                    phase = next.stage.databaseName,
                    startedAtUtcMs = eventUtcMs,
                    endedAtUtcMs = null,
                ),
            )
        }
    }
}

internal val TimerStage.isActive: Boolean
    get() = this == TimerStage.Warmup || this == TimerStage.Focus

internal val TimerStage.isTerminal: Boolean
    get() = this == TimerStage.Completed || this == TimerStage.Aborted

private val TimerStage.databaseName: String
    get() = when (this) {
        TimerStage.Idle -> "IDLE"
        TimerStage.Warmup -> "WARMUP"
        TimerStage.Focus -> "FOCUS"
        TimerStage.Paused -> "PAUSED"
        TimerStage.Completed -> "COMPLETED"
        TimerStage.Aborted -> "ABORTED"
    }

private fun String.toTimerStage(): TimerStage = when (this) {
    "IDLE" -> TimerStage.Idle
    "WARMUP" -> TimerStage.Warmup
    "FOCUS" -> TimerStage.Focus
    "PAUSED" -> TimerStage.Paused
    "COMPLETED" -> TimerStage.Completed
    "ABORTED" -> TimerStage.Aborted
    else -> error("Unknown timer stage: $this")
}

private fun TimerSnapshot.toCheckpoint(
    bootCount: Int,
    previousStage: String?,
): TimerCheckpointEntity = TimerCheckpointEntity(
    sessionId = sessionId,
    stage = stage.databaseName,
    previousStage = previousStage,
    totalDurationMs = totalDurationMs,
    warmupDurationMs = warmupDurationMs,
    phaseStartedElapsedMs = phaseStartedElapsedMs,
    deadlineElapsedMs = phaseDeadlineElapsedMs,
    deadlineUtcMs = phaseDeadlineUtcMs,
    accumulatedWarmupMs = accumulatedWarmupMs,
    accumulatedFocusMs = accumulatedFocusMs,
    bootCount = bootCount,
    updatedAtUtcMs = updatedAtUtcMs,
)

private fun TimerCheckpointEntity.toSnapshot(): TimerSnapshot = TimerSnapshot(
    sessionId = sessionId,
    stage = stage.toTimerStage(),
    totalDurationMs = totalDurationMs,
    warmupDurationMs = warmupDurationMs,
    mode = if (totalDurationMs == 0L && warmupDurationMs == 0L) {
        TimerMode.STOPWATCH
    } else {
        TimerMode.COUNTDOWN
    },
    accumulatedWarmupMs = accumulatedWarmupMs,
    accumulatedFocusMs = accumulatedFocusMs,
    phaseStartedElapsedMs = phaseStartedElapsedMs,
    phaseDeadlineElapsedMs = deadlineElapsedMs,
    phaseDeadlineUtcMs = deadlineUtcMs,
    updatedAtUtcMs = updatedAtUtcMs,
)

private fun TimerSnapshot.rebaseElapsedClock(clock: ClockSource): TimerSnapshot {
    if (mode == TimerMode.STOPWATCH) {
        val elapsedSincePersistMs = (clock.utcNowMs() - updatedAtUtcMs).coerceAtLeast(0L)
        return copy(
            phaseStartedElapsedMs = clock.elapsedRealtimeMs(),
            accumulatedFocusMs = accumulatedFocusMs + elapsedSincePersistMs,
            updatedAtUtcMs = clock.utcNowMs(),
        )
    }
    val deadlineUtc = requireNotNull(phaseDeadlineUtcMs)
    val phaseDurationRemainingAtStart = when (stage) {
        TimerStage.Warmup -> warmupDurationMs - accumulatedWarmupMs
        TimerStage.Focus -> totalDurationMs - warmupDurationMs - accumulatedFocusMs
        else -> error("Only active snapshots can be rebased")
    }
    val deadlineElapsed = clock.elapsedRealtimeMs() + (deadlineUtc - clock.utcNowMs())
    return copy(
        phaseStartedElapsedMs = deadlineElapsed - phaseDurationRemainingAtStart,
        phaseDeadlineElapsedMs = deadlineElapsed,
        updatedAtUtcMs = clock.utcNowMs(),
    )
}
