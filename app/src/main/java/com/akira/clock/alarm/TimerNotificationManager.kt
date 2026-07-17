package com.akira.clock.alarm

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.akira.clock.R
import com.akira.clock.timer.TimerSnapshot
import com.akira.clock.timer.TimerStage
import com.akira.clock.ui.MainActivity

class TimerNotificationManager(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "计时提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "预热及专注阶段结束提醒"
            },
        )
    }

    fun show(snapshot: TimerSnapshot, alert: Boolean = false) {
        if (!notificationsAllowed()) return
        createChannel()
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_clock)
            .setContentTitle(stageTitle(snapshot.stage))
            .setContentText(contentText(snapshot))
            .setContentIntent(openAppPendingIntent())
            .setAutoCancel(snapshot.stage == TimerStage.Completed || snapshot.stage == TimerStage.Aborted)
            .setOnlyAlertOnce(!alert)
            .setCategory(Notification.CATEGORY_ALARM)

        when (snapshot.stage) {
            TimerStage.Warmup, TimerStage.Focus -> {
                val deadline = snapshot.phaseDeadlineUtcMs
                if (deadline != null) {
                    builder.setUsesChronometer(true)
                        .setChronometerCountDown(true)
                        .setWhen(deadline)
                        .setOngoing(true)
                }
                builder.addAction(action("暂停", PhaseAlarmReceiver.ACTION_PAUSE, snapshot.sessionId))
                builder.addAction(action("结束", PhaseAlarmReceiver.ACTION_ABORT, snapshot.sessionId))
            }
            TimerStage.Paused -> {
                builder.setOngoing(true)
                builder.addAction(action("继续", PhaseAlarmReceiver.ACTION_RESUME, snapshot.sessionId))
                builder.addAction(action("结束", PhaseAlarmReceiver.ACTION_ABORT, snapshot.sessionId))
            }
            else -> Unit
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    fun cancel() = manager.cancel(NOTIFICATION_ID)

    private fun action(label: String, action: String, sessionId: String): Notification.Action {
        val intent = Intent(context, PhaseAlarmReceiver::class.java).apply {
            this.action = action
            putExtra(PhaseAlarmReceiver.EXTRA_SESSION_ID, sessionId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(null, label, pendingIntent).build()
    }

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        7002,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun stageTitle(stage: TimerStage): String = when (stage) {
        TimerStage.Warmup -> "预热中"
        TimerStage.Focus -> "专注中"
        TimerStage.Paused -> "计时已暂停"
        TimerStage.Completed -> "任务已完成"
        TimerStage.Aborted -> "任务已结束"
        TimerStage.Idle -> "Clock"
    }

    private fun contentText(snapshot: TimerSnapshot): String = when (snapshot.stage) {
        TimerStage.Warmup -> "预热结束后将自动进入专注阶段"
        TimerStage.Focus -> "保持专注，阶段结束时会提醒你"
        TimerStage.Paused -> "暂停时间不会计入投入时长"
        TimerStage.Completed -> "本次投入已保存"
        TimerStage.Aborted -> "本次实际投入已保存"
        TimerStage.Idle -> "尚未开始任务"
    }

    companion object {
        const val CHANNEL_ID = "timer_phase"
        const val NOTIFICATION_ID = 7100
    }
}
