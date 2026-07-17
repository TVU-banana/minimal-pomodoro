package com.akira.clock.alarm

import android.content.Context
import android.provider.Settings
import androidx.room.withTransaction
import com.akira.clock.data.ClockDatabase
import com.akira.clock.data.SessionEntity
import com.akira.clock.data.SessionSegmentEntity
import com.akira.clock.data.SessionStatus
import com.akira.clock.data.TimerCheckpointEntity
import com.akira.clock.timer.AndroidClockSource
import com.akira.clock.timer.ClockSource
import com.akira.clock.timer.TimerCommand
import com.akira.clock.timer.TimerSnapshot
import com.akira.clock.timer.TimerStage
import com.akira.clock.timer.reduce
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single write path for timer state. Android entry points dispatch commands here instead of
 * changing Room rows, alarms, or notifications independently.
 */
class TimerCoordinator internal constructor(
    private val database: ClockDatabase,
    private val clock: ClockSource,
    private val alarmScheduler: PhaseAlarmScheduler,
    private val notifications: TimerNotificationManager,
    private val bootCount: () -> Int,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<TimerSnapshot?>(null)
    val state: StateFlow<TimerSnapshot?> = mutableState.asStateFlow()

    suspend fun start(presetId: String): TimerSnapshot = mutex.withLock {
        val checkpoint = database.timerCheckpointDao().get()
        require(checkpoint == null) { "A timer is already active" }
        val preset = requireNotNull(database.taskPresetDao().get(presetId)) {
            "Unknown preset: $presetId"
        }
        val category = requireNotNull(database.categoryDao().get(preset.categoryId)) {
            "Preset category no longer exists: ${preset.categoryId}"
        }
        if (!alarmScheduler.canScheduleExactAlarms()) throw ExactAlarmUnavailableException()

        val next = requireNotNull(
            reduce(
                null,
                TimerCommand.Start(preset.id, preset.totalDurationMs, preset.warmupDurationMs),
                clock,
            ),
        )
        schedule(next)
        val session = SessionEntity(
            id = next.sessionId,
            presetId = preset.id,
            categoryId = category.id,
            categoryNameSnapshot = category.name,
            taskNameSnapshot = preset.name,
            plannedTotalMs = next.totalDurationMs,
            plannedWarmupMs = next.warmupDurationMs,
            actualWarmupMs = 0L,
            actualFocusMs = 0L,
            startedAtUtcMs = next.updatedAtUtcMs,
            endedAtUtcMs = null,
            timezoneId = ZoneId.systemDefault().id,
            status = SessionStatus.RUNNING.name,
            createdAtUtcMs = next.updatedAtUtcMs,
            updatedAtUtcMs = next.updatedAtUtcMs,
        )
        persistTransition(null, next, session)
        publish(next)
        next
    }

    suspend fun pause(): TimerSnapshot? = pause(expectedSessionId = null)

    suspend fun pause(expectedSessionId: String?): TimerSnapshot? = mutex.withLock {
        dispatchLocked(TimerCommand.Pause, expectedSessionId)
    }

    suspend fun resume(): TimerSnapshot? = resume(expectedSessionId = null)

    suspend fun resume(expectedSessionId: String?): TimerSnapshot? = mutex.withLock {
        val current = loadCurrentLocked() ?: return@withLock null
        if (expectedSessionId != null && current.sessionId != expectedSessionId) return@withLock current
        if (!alarmScheduler.canScheduleExactAlarms()) throw ExactAlarmUnavailableException()
        transitionLocked(current, TimerCommand.Resume)
    }

    suspend fun abort(): TimerSnapshot? = abort(expectedSessionId = null)

    suspend fun abort(expectedSessionId: String?): TimerSnapshot? = mutex.withLock {
        dispatchLocked(TimerCommand.Abort, expectedSessionId)
    }

    /** Ignores an older alarm, while allowing a later alarm to catch up an overdue checkpoint. */
    suspend fun handleDeadline(sessionId: String, expectedDeadlineUtcMs: Long): TimerSnapshot? =
        mutex.withLock {
            var current = loadCurrentLocked() ?: return@withLock null
            if (current.sessionId != sessionId || !current.stage.isActive) return@withLock current
            val storedDeadline = current.phaseDeadlineUtcMs ?: return@withLock current
            if (!deadlineIsEligible(expectedDeadlineUtcMs, storedDeadline)) {
                return@withLock current
            }
            while (current.stage.isActive &&
                clock.elapsedRealtimeMs() >= requireNotNull(current.phaseDeadlineElapsedMs)
            ) {
                current = transitionLocked(current, TimerCommand.PhaseDeadlineReached)
            }
            current
        }

    /** Restores process death and rebases elapsedRealtime values after a device reboot. */
    suspend fun restore(forceRebaseElapsedClock: Boolean = false): TimerSnapshot? = mutex.withLock {
        restoreLocked(forceRebaseElapsedClock)
    }

    private suspend fun dispatchLocked(
        command: TimerCommand,
        expectedSessionId: String?,
    ): TimerSnapshot? {
        val current = loadCurrentLocked() ?: return null
        if (expectedSessionId != null && current.sessionId != expectedSessionId) return current
        return transitionLocked(current, command)
    }

    private suspend fun transitionLocked(
        current: TimerSnapshot,
        command: TimerCommand,
    ): TimerSnapshot {
        val next = requireNotNull(reduce(current, command, clock))
        if (next == current) return current

        if (next.stage.isActive) schedule(next) else alarmScheduler.cancel()
        val session = requireNotNull(database.sessionDao().get(current.sessionId))
        persistTransition(current, next, sessionFor(current, next, session))
        publish(
            next,
            alert = command == TimerCommand.PhaseDeadlineReached || command == TimerCommand.Abort,
        )
        return next
    }

    private suspend fun loadCurrentLocked(): TimerSnapshot? =
        mutableState.value ?: restoreLocked(forceRebaseElapsedClock = false)

    private suspend fun restoreLocked(forceRebaseElapsedClock: Boolean): TimerSnapshot? {
        val checkpoint = database.timerCheckpointDao().get()
        if (checkpoint == null) {
            mutableState.value = null
            notifications.cancel()
            return null
        }
        var restored = checkpoint.toSnapshot()
        val currentBootCount = bootCount()
        if (restored.stage.isActive &&
            (forceRebaseElapsedClock || checkpoint.bootCount != currentBootCount)
        ) {
            restored = restored.rebaseElapsedClock(clock)
            val session = requireNotNull(database.sessionDao().get(restored.sessionId))
            database.withTransaction {
                database.timerCheckpointDao().upsert(restored.toCheckpoint(currentBootCount, checkpoint.previousStage))
                database.sessionDao().upsert(session.copy(updatedAtUtcMs = clock.utcNowMs()))
            }
        }
        mutableState.value = restored

        while (restored.stage.isActive &&
            clock.elapsedRealtimeMs() >= requireNotNull(restored.phaseDeadlineElapsedMs)
        ) {
            restored = transitionLocked(restored, TimerCommand.PhaseDeadlineReached)
        }
        if (restored.stage.isActive) schedule(restored) else alarmScheduler.cancel()
        publish(restored)
        return restored
    }

    private suspend fun persistTransition(
        previous: TimerSnapshot?,
        next: TimerSnapshot,
        session: SessionEntity,
    ) {
        database.withTransaction {
            // The session is inserted first to satisfy the segment foreign key on Start.
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

    private suspend fun updateSegments(previous: TimerSnapshot?, next: TimerSnapshot) {
        val segmentDao = database.sessionSegmentDao()
        val eventUtcMs = when {
            previous?.stage == TimerStage.Warmup && next.stage == TimerStage.Focus ->
                previous.phaseDeadlineUtcMs ?: next.updatedAtUtcMs
            previous?.stage == TimerStage.Focus && next.stage == TimerStage.Completed ->
                previous.phaseDeadlineUtcMs ?: next.updatedAtUtcMs
            else -> next.updatedAtUtcMs
        }
        val shouldClose = previous != null && previous.stage.isActive && previous.stage != next.stage ||
            next.stage == TimerStage.Paused || next.stage.isTerminal
        if (shouldClose) {
            segmentDao.openForSession(next.sessionId)?.let { open ->
                segmentDao.upsert(open.copy(endedAtUtcMs = eventUtcMs.coerceAtLeast(open.startedAtUtcMs)))
            }
        }
        val shouldOpen = next.stage.isActive && (previous == null || previous.stage != next.stage)
        if (shouldOpen) {
            segmentDao.upsert(
                SessionSegmentEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = next.sessionId,
                    phase = next.stage.databaseName,
                    startedAtUtcMs = eventUtcMs,
                    endedAtUtcMs = null,
                ),
            )
        }
    }

    private fun sessionFor(
        previous: TimerSnapshot,
        snapshot: TimerSnapshot,
        existing: SessionEntity,
    ): SessionEntity =
        existing.copy(
            actualWarmupMs = snapshot.accumulatedWarmupMs,
            actualFocusMs = snapshot.accumulatedFocusMs,
            endedAtUtcMs = when (snapshot.stage) {
                TimerStage.Completed -> previous.phaseDeadlineUtcMs ?: snapshot.updatedAtUtcMs
                TimerStage.Aborted -> snapshot.updatedAtUtcMs
                else -> null
            },
            status = when (snapshot.stage) {
                TimerStage.Paused -> SessionStatus.PAUSED.name
                TimerStage.Completed -> SessionStatus.COMPLETED.name
                TimerStage.Aborted -> SessionStatus.ABORTED.name
                else -> SessionStatus.RUNNING.name
            },
            updatedAtUtcMs = snapshot.updatedAtUtcMs,
        )

    private fun schedule(snapshot: TimerSnapshot) {
        alarmScheduler.schedule(
            snapshot.sessionId,
            requireNotNull(snapshot.phaseDeadlineElapsedMs),
            requireNotNull(snapshot.phaseDeadlineUtcMs),
        )
    }

    private fun publish(snapshot: TimerSnapshot, alert: Boolean = false) {
        mutableState.value = snapshot
        notifications.show(snapshot, alert)
    }

    companion object {
        @Volatile private var instance: TimerCoordinator? = null

        fun get(context: Context): TimerCoordinator = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(context: Context): TimerCoordinator = TimerCoordinator(
            database = ClockDatabase.get(context),
            clock = AndroidClockSource(),
            alarmScheduler = AndroidPhaseAlarmScheduler(context),
            notifications = TimerNotificationManager(context),
            bootCount = {
                Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
            },
        )
    }
}

internal fun deadlineIsEligible(
    receivedDeadlineUtcMs: Long,
    storedDeadlineUtcMs: Long,
): Boolean = receivedDeadlineUtcMs >= storedDeadlineUtcMs

private val TimerStage.isActive: Boolean
    get() = this == TimerStage.Warmup || this == TimerStage.Focus

private val TimerStage.isTerminal: Boolean
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

private fun TimerSnapshot.toCheckpoint(bootCount: Int, previousStage: String?): TimerCheckpointEntity =
    TimerCheckpointEntity(
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
    accumulatedWarmupMs = accumulatedWarmupMs,
    accumulatedFocusMs = accumulatedFocusMs,
    phaseStartedElapsedMs = phaseStartedElapsedMs,
    phaseDeadlineElapsedMs = deadlineElapsedMs,
    phaseDeadlineUtcMs = deadlineUtcMs,
    updatedAtUtcMs = updatedAtUtcMs,
)

private fun TimerSnapshot.rebaseElapsedClock(clock: ClockSource): TimerSnapshot {
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
