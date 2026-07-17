package com.akira.clock.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PhaseAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching {
                    val coordinator = TimerCoordinator.get(context)
                    when (intent.action) {
                        ACTION_PHASE_DEADLINE -> coordinator.handleDeadline(
                            sessionId,
                            intent.getLongExtra(EXTRA_DEADLINE_UTC_MS, Long.MIN_VALUE),
                        )
                        ACTION_PAUSE -> coordinator.pause(sessionId)
                        ACTION_RESUME -> coordinator.resume(sessionId)
                        ACTION_ABORT -> coordinator.abort(sessionId)
                    }
                }.onFailure { error ->
                    Log.e(TAG, "Timer broadcast failed", error)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_PHASE_DEADLINE = "com.akira.clock.action.PHASE_DEADLINE"
        const val ACTION_PAUSE = "com.akira.clock.action.PAUSE"
        const val ACTION_RESUME = "com.akira.clock.action.RESUME"
        const val ACTION_ABORT = "com.akira.clock.action.ABORT"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_DEADLINE_UTC_MS = "deadline_utc_ms"
        private const val TAG = "PhaseAlarmReceiver"
    }
}
