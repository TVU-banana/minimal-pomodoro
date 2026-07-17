package com.akira.clock.alarm

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

interface PhaseAlarmScheduler {
    fun canScheduleExactAlarms(): Boolean
    fun schedule(sessionId: String, deadlineElapsedMs: Long, deadlineUtcMs: Long)
    fun cancel()
}

class ExactAlarmUnavailableException : IllegalStateException(
    "Exact alarms are unavailable. The timer was not started.",
)

class AndroidPhaseAlarmScheduler(
    private val context: Context,
    private val alarmManager: AlarmManager =
        context.getSystemService(AlarmManager::class.java),
) : PhaseAlarmScheduler {
    override fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    @SuppressLint("ScheduleExactAlarm", "MissingPermission")
    override fun schedule(sessionId: String, deadlineElapsedMs: Long, deadlineUtcMs: Long) {
        if (!canScheduleExactAlarms()) throw ExactAlarmUnavailableException()
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            deadlineElapsedMs,
            phasePendingIntent(sessionId, deadlineUtcMs),
        )
    }

    override fun cancel() {
        alarmManager.cancel(phasePendingIntent("", 0L))
    }

    private fun phasePendingIntent(sessionId: String, deadlineUtcMs: Long): PendingIntent {
        val intent = Intent(context, PhaseAlarmReceiver::class.java).apply {
            action = PhaseAlarmReceiver.ACTION_PHASE_DEADLINE
            putExtra(PhaseAlarmReceiver.EXTRA_SESSION_ID, sessionId)
            putExtra(PhaseAlarmReceiver.EXTRA_DEADLINE_UTC_MS, deadlineUtcMs)
        }
        return PendingIntent.getBroadcast(
            context,
            PHASE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val PHASE_REQUEST_CODE = 7001
    }
}
