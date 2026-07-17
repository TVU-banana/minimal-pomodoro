package com.akira.clock.ui.render

import com.akira.clock.timer.TimerStage
import java.util.Locale
import kotlin.math.ceil

internal object TimerRenderMath {
    fun formatRemaining(remainingMs: Long): String {
        val totalSeconds = ceil(remainingMs.coerceAtLeast(0L) / 1_000.0).toLong()
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }

    fun stageLabel(stage: TimerStage): String = when (stage) {
        TimerStage.Idle -> "准备开始"
        TimerStage.Warmup -> "启动阶段"
        TimerStage.Focus -> "正式专注"
        TimerStage.Paused -> "已暂停"
        TimerStage.Completed -> "已完成"
        TimerStage.Aborted -> "已结束"
    }

    fun accessibilityDescription(state: TimerUiState): String = buildString(64) {
        append(state.taskName)
        if (state.categoryName.isNotBlank()) {
            append("，")
            append(state.categoryName)
        }
        append("，")
        append(if (state.paused) "已暂停" else stageLabel(state.stage))
        append("，剩余 ")
        append(formatRemaining(state.remainingMs))
    }
}
