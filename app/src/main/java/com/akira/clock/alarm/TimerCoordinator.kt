package com.akira.clock.alarm

import android.content.Context
import android.provider.Settings
import com.akira.clock.band.BandAction
import com.akira.clock.band.BandCode
import com.akira.clock.band.BandCommandGate
import com.akira.clock.band.BandControlCodec
import com.akira.clock.band.BandReply
import com.akira.clock.band.BandRequest
import com.akira.clock.band.BandState
import com.akira.clock.band.bandState
import com.akira.clock.data.ClockDatabase
import com.akira.clock.timer.AndroidClockSource
import com.akira.clock.timer.ClockSource
import com.akira.clock.timer.IdentifierSource
import com.akira.clock.timer.TimerCommand
import com.akira.clock.timer.TimerMode
import com.akira.clock.timer.TimerSnapshot
import com.akira.clock.timer.TimerStage
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
    private val vintageAlarmPlayer: VintageAlarmPlayer,
    private val identifiers: IdentifierSource,
) {
    private val mutex = Mutex()
    private val bandGate = BandCommandGate { java.util.UUID.randomUUID().toString() }
    private val mutableState = MutableStateFlow<TimerSnapshot?>(null)

    /** Current process-local snapshot. Room remains the durable source of truth. */
    val state: StateFlow<TimerSnapshot?> = mutableState.asStateFlow()

    suspend fun start(presetId: String): TimerSnapshot = mutex.withLock {
        startLocked(presetId)
    }

    private suspend fun startLocked(presetId: String): TimerSnapshot {
        require(store.checkpoint() == null) { "A timer is already active" }
        val startContext = store.loadStartContext(presetId)
        if (startContext.preset.timerMode == TimerMode.COUNTDOWN.name &&
            !alarmScheduler.canScheduleExactAlarms()
        ) throw ExactAlarmUnavailableException()

        val next = requireNotNull(
            reduce(
                current = null,
                command = TimerCommand.Start.forTimeBlock(
                    startContext.preset.id,
                    startContext.preset.totalDurationMs,
                    startContext.preset.warmupDurationMs,
                    TimerMode.valueOf(startContext.preset.timerMode),
                ),
                clock = clock,
                identifiers = identifiers,
            ),
        )

        // Schedule first so a process death after the Room commit cannot silently miss a deadline.
        // A stale alarm is safe because its session ID and UTC deadline are validated on delivery.
        if (next.mode == TimerMode.COUNTDOWN) schedule(next)
        store.persist(null, next, store.createSession(next, startContext))
        publish(next)
        return next
    }

    /**
     * Executes validated band requests under the same lock as UI and alarm commands.
     *
     * Transport adapters must authenticate their peer before calling this internal entry point.
     * Cancellation or ambiguous persistence invalidates the token; retry requires state refresh
     * and an explicit new user action. No exported Android component exposes this method.
     */
    suspend fun handleBandMessage(bytes: ByteArray): ByteArray {
        val request = BandControlCodec.decode(bytes)
        return BandControlCodec.encode(handleBandRequest(request))
    }

    private suspend fun handleBandRequest(request: BandRequest): BandReply = mutex.withLock {
        val current = reconcileBandStateLocked()
        val currentState = bandStateLocked(current)
        if (request.action == BandAction.STATE) {
            return@withLock BandReply(request.id, BandCode.OK, currentState)
        }
        if (request.action == BandAction.PRESETS) {
            val page = store.bandPresets(request.offset)
            return@withLock BandReply(
                request.id, BandCode.OK, currentState, page.first, page.second,
            )
        }
        bandGate.replay(request, currentState)?.let { return@withLock it }
        var code = when {
            request.expectedToken != bandGate.token -> BandCode.STALE
            request.action == BandAction.START && current?.stage?.isActiveOrPaused() == true ->
                BandCode.INVALID_STATE
            request.action != BandAction.START && current?.sessionId != request.sessionId ->
                BandCode.STALE
            current != null && current.mode != TimerMode.COUNTDOWN &&
                request.action != BandAction.START -> BandCode.INVALID_STATE
            else -> BandCode.OK
        }
        if (code == BandCode.OK) {
            try {
                when (request.action) {
                    BandAction.START -> {
                        val presetId = requireNotNull(request.presetId)
                        if (!store.isBandPreset(presetId)) {
                            code = BandCode.NOT_FOUND
                        } else {
                            startLocked(presetId)
                        }
                    }
                    BandAction.PAUSE -> {
                        if (current?.stage?.isActive == true) {
                            transitionLocked(current, TimerCommand.Pause)
                        } else code = BandCode.INVALID_STATE
                    }
                    BandAction.RESUME -> {
                        if (current?.stage != TimerStage.Paused) {
                            code = BandCode.INVALID_STATE
                        } else if (!alarmScheduler.canScheduleExactAlarms()) {
                            code = BandCode.ALARM_UNAVAILABLE
                        } else transitionLocked(current, TimerCommand.Resume)
                    }
                    BandAction.ABORT -> {
                        if (current?.stage?.isActiveOrPaused() == true) {
                            transitionLocked(current, TimerCommand.Abort)
                        } else code = BandCode.INVALID_STATE
                    }
                    else -> error("Read requests are handled before mutation")
                }
            } catch (error: ExactAlarmUnavailableException) {
                code = BandCode.ALARM_UNAVAILABLE
            } catch (error: Throwable) {
                bandGate.invalidate()
                // Re-read Room next time: a commit may have succeeded before a system
                // effect failed.
                mutableState.value = null
                throw error
            }
        }
        BandReply(request.id, code, bandStateLocked(mutableState.value)).also {
            bandGate.remember(request, it)
        }
    }

    private suspend fun reconcileBandStateLocked(): TimerSnapshot? {
        try {
            var current = loadCurrentLocked() ?: return null
            while (current.mode == TimerMode.COUNTDOWN && current.stage.isActive &&
                clock.elapsedRealtimeMs() >= requireNotNull(current.phaseDeadlineElapsedMs)
            ) {
                current = transitionLocked(current, TimerCommand.PhaseDeadlineReached)
            }
            return current
        } catch (error: Throwable) {
            bandGate.invalidate()
            mutableState.value = null
            throw error
        }
    }

    private suspend fun bandStateLocked(snapshot: TimerSnapshot?): BandState = bandState(
        snapshot, bandGate.token, clock.elapsedRealtimeMs(),
        snapshot?.takeUnless { it.stage.isTerminal }
            ?.let { store.session(it.sessionId).taskNameSnapshot.take(80) } ?: "",
    )

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
        if (current.mode == TimerMode.COUNTDOWN && !alarmScheduler.canScheduleExactAlarms()) {
            throw ExactAlarmUnavailableException()
        }
        transitionLocked(current, TimerCommand.Resume)
    }

    suspend fun abort(
        expectedSessionId: String? = null,
        reason: String? = null,
    ): TimerSnapshot? = mutex.withLock {
        val current = loadCurrentLocked() ?: return@withLock null
        if (expectedSessionId != null && current.sessionId != expectedSessionId) {
            return@withLock current
        }
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
        if (current.sessionId != sessionId || !current.stage.isActive ||
            current.mode == TimerMode.STOPWATCH
        ) return@withLock current
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

        if (next.stage.isActive && next.mode == TimerMode.COUNTDOWN) {
            schedule(next)
        } else {
            alarmScheduler.cancel()
        }
        val session = store.session(current.sessionId)
        store.persist(current, next, store.updateSession(current, next, session, abortReason))
        publish(
            next,
            alert = command == TimerCommand.PhaseDeadlineReached || command == TimerCommand.Abort,
        )
        if (next.stage == TimerStage.Completed) vintageAlarmPlayer.playCompletionPattern()
        return next
    }

    private suspend fun loadCurrentLocked(): TimerSnapshot? =
        mutableState.value ?: restoreLocked(forceRebaseElapsedClock = false)

    private suspend fun restoreLocked(forceRebaseElapsedClock: Boolean): TimerSnapshot? {
        val checkpoint = store.checkpoint()
        if (checkpoint == null) {
            if (mutableState.value != null) bandGate.invalidate()
            mutableState.value = null
            notifications.cancel()
            return null
        }
        var restored = store.restore(checkpoint, forceRebaseElapsedClock)
        mutableState.value = restored

        while (restored.mode == TimerMode.COUNTDOWN && restored.stage.isActive &&
            clock.elapsedRealtimeMs() >= requireNotNull(restored.phaseDeadlineElapsedMs)
        ) {
            restored = transitionLocked(restored, TimerCommand.PhaseDeadlineReached)
        }
        if (restored.stage.isActive && restored.mode == TimerMode.COUNTDOWN) {
            schedule(restored)
        } else {
            alarmScheduler.cancel()
        }
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
        bandGate.invalidate()
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
                vintageAlarmPlayer = VintageAlarmPlayer(),
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

private fun TimerStage.isActiveOrPaused(): Boolean = isActive || this == TimerStage.Paused
