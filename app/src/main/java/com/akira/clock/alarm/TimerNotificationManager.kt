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

/** Builds phase-only notifications without per-second application updates. */
internal class TimerNotificationManager(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notification_channel_description)
            },
        )
    }

    fun show(snapshot: TimerSnapshot, alert: Boolean = false) {
        if (!notificationsAllowed()) return
        createChannel()
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_timer)
            .setContentTitle(stageTitle(snapshot.stage))
            .setContentText(contentText(snapshot))
            .setContentIntent(openAppPendingIntent())
            .setAutoCancel(
                snapshot.stage == TimerStage.Completed || snapshot.stage == TimerStage.Aborted
            )
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
                builder.addAction(
                    action(
                        context.getString(R.string.notification_action_pause),
                        PhaseAlarmReceiver.ACTION_PAUSE,
                        snapshot.sessionId,
                    ),
                )
                builder.addAction(
                    action(
                        context.getString(R.string.notification_action_abort),
                        PhaseAlarmReceiver.ACTION_ABORT,
                        snapshot.sessionId,
                    ),
                )
            }
            TimerStage.Paused -> {
                builder.setOngoing(true)
                builder.addAction(
                    action(
                        context.getString(R.string.notification_action_resume),
                        PhaseAlarmReceiver.ACTION_RESUME,
                        snapshot.sessionId,
                    ),
                )
                builder.addAction(
                    action(
                        context.getString(R.string.notification_action_abort),
                        PhaseAlarmReceiver.ACTION_ABORT,
                        snapshot.sessionId,
                    ),
                )
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

    private fun stageTitle(stage: TimerStage): String = context.getString(when (stage) {
        TimerStage.Warmup -> R.string.notification_title_warmup
        TimerStage.Focus -> R.string.notification_title_focus
        TimerStage.Paused -> R.string.notification_title_paused
        TimerStage.Completed -> R.string.notification_title_completed
        TimerStage.Aborted -> R.string.notification_title_aborted
        TimerStage.Idle -> R.string.app_name
    })

    private fun contentText(snapshot: TimerSnapshot): String = context.getString(
        when (snapshot.stage) {
            TimerStage.Warmup -> R.string.notification_content_warmup
            TimerStage.Focus -> R.string.notification_content_focus
            TimerStage.Paused -> R.string.notification_content_paused
            TimerStage.Completed -> R.string.notification_content_completed
            TimerStage.Aborted -> R.string.notification_content_aborted
            TimerStage.Idle -> R.string.notification_content_idle
        },
    )

    companion object {
        const val CHANNEL_ID = "timer_phase"
        const val NOTIFICATION_ID = 7100
    }
}
