package com.akira.clock.ui.render

import java.util.Locale
import kotlin.math.ceil

/** Allocation-light formatting helpers shared by Canvas renderers. */
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
}
