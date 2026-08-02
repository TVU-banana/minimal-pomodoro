package com.akira.clock.alarm

import android.content.Context
import android.provider.Settings
import com.akira.clock.data.ClockDatabase
import com.akira.clock.timer.AndroidClockSource
import com.akira.clock.timer.ClockSource
import com.akira.clock.timer.IdentifierSource
import com.akira.clock.timer.TimerCommand
import com.akira.clock.timer.TimerSnapshot
import com.akira.clock.timer.UuidIdentifierSource
import com.akira.clock.timer.reduce
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes timer commands and coordinates persistence with Android system effects.
 *
 * UI and BroadcastReceiver entry points must dispatch commands here rather than changing Room,
 * AlarmManager, notifications, or the in-memory snapshot independently. [TimerTransitionStore]
 * owns database transactions; this class owns command ordering and reconciliation.
 */
internal class TimerCoordinator private constructor(
    private val store: TimerTransitionStore,
    private val clock: ClockSource,
    private val alarmScheduler: PhaseAlarmScheduler,
    private val notifications: TimerNotificationManager,
    private val identifiers: IdentifierSource,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<TimerSnapshot?>(null)

    /** Current process-local snapshot. Room remains the durable source of truth. */
    val state: StateFlow<TimerSnapshot?> = mutableState.asStateFlow()

    suspend fun start(presetId: String): TimerSnapshot = mutex.withLock {
        require(store.checkpoint() == null) { "A timer is already active" }
        val startContext = store.loadStartContext(presetId)
        if (!alarmScheduler.canScheduleExactAlarms()) throw ExactAlarmUnavailableException()

        val next = requireNotNull(
            reduce(
                current = null,
                command = TimerCommand.Start(
                    startContext.preset.id,
                    startContext.preset.totalDurationMs,
                    startContext.preset.warmupDurationMs,
                ),
                clock = clock,
                identifiers = identifiers,
            ),
        )

        // Schedule first so a process death after the Room commit cannot silently miss a deadline.
        // A stale alarm is safe because its session ID and UTC deadline are validated on delivery.
        schedule(next)
        store.persist(null, next, store.createSession(next, startContext))
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
        if (expectedSessionId != null && current.sessionId != expectedSessionId) {
            return@withLock current
        }
        if (!alarmScheduler.canScheduleExactAlarms()) throw ExactAlarmUnavailableException()
        transitionLocked(current, TimerCommand.Resume)
    }

    suspend fun abort(
        expectedSessionId: String? = null,
        reason: String? = null,
    ): TimerSnapshot? = mutex.withLock {
        val current = loadCurrentLocked() ?: return@withLock null
        if (expectedSessionId != null && current.sessionId != expectedSessionId) return@withLock current
        transitionLocked(current, TimerCommand.Abort, abortReason = reason)
    }

    /**
     * Handles an alarm only when it belongs to the current phase.
     *
     * Older duplicate broadcasts are ignored. A later eligible broadcast may advance multiple
     * overdue phases after an interrupted persistence or delayed delivery.
     */
    suspend fun handleDeadline(
        sessionId: String,
        expectedDeadlineUtcMs: Long,
    ): TimerSnapshot? = mutex.withLock {
        var current = loadCurrentLocked() ?: return@withLock null
        if (current.sessionId != sessionId || !current.stage.isActive) return@withLock current
        val storedDeadline = current.phaseDeadlineUtcMs ?: return@withLock current
        if (!deadlineIsEligible(expectedDeadlineUtcMs, storedDeadline)) return@withLock current

        while (current.stage.isActive &&
            clock.elapsedRealtimeMs() >= requireNotNull(current.phaseDeadlineElapsedMs)
        ) {
            current = transitionLocked(current, TimerCommand.PhaseDeadlineReached)
        }
        current
    }

    /** Restores process death and rebases monotonic deadlines after a device reboot. */
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
        abortReason: String? = null,
    ): TimerSnapshot {
        val next = requireNotNull(reduce(current, command, clock, identifiers))
        if (next == current) return current

        if (next.stage.isActive) schedule(next) else alarmScheduler.cancel()
        val session = store.session(current.sessionId)
        store.persist(current, next, store.updateSession(current, next, session, abortReason))
        publish(
            next,
            alert = command == TimerCommand.PhaseDeadlineReached || command == TimerCommand.Abort,
        )
        return next
    }

    private suspend fun loadCurrentLocked(): TimerSnapshot? =
        mutableState.value ?: restoreLocked(forceRebaseElapsedClock = false)

    private suspend fun restoreLocked(forceRebaseElapsedClock: Boolean): TimerSnapshot? {
        val checkpoint = store.checkpoint()
        if (checkpoint == null) {
            mutableState.value = null
            notifications.cancel()
            return null
        }
        var restored = store.restore(checkpoint, forceRebaseElapsedClock)
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

        /** Returns the process-wide coordinator backed by the application database. */
        fun get(context: Context): TimerCoordinator = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(context: Context): TimerCoordinator {
            val clock = AndroidClockSource()
            val identifiers = UuidIdentifierSource
            val bootCount = {
                Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
            }
            val store = TimerTransitionStore(
                database = ClockDatabase.get(context),
                clock = clock,
                bootCount = bootCount,
                identifiers = identifiers,
            )
            return TimerCoordinator(
                store = store,
                clock = clock,
                alarmScheduler = AndroidPhaseAlarmScheduler(context),
                notifications = TimerNotificationManager(context),
                identifiers = identifiers,
            )
        }
    }
}

/** Returns whether an alarm deadline may advance the currently stored phase. */
internal fun deadlineIsEligible(
    receivedDeadlineUtcMs: Long,
    storedDeadlineUtcMs: Long,
): Boolean = receivedDeadlineUtcMs >= storedDeadlineUtcMs
