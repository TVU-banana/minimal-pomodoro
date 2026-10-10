package com.akira.clock.alarm

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * Plays the short completion alarm on the alarm stream.
 *
 * The pattern is deliberately finite: three beeps, a short pause, then three more beeps. This
 * keeps a completed countdown audible without creating a persistent playback lifecycle.
 */
internal class VintageAlarmPlayer {
    private val handler = Handler(Looper.getMainLooper())
    private var toneGenerator: ToneGenerator? = null

    fun playCompletionPattern() {
        handler.removeCallbacksAndMessages(null)
        toneGenerator?.release()
        val generator = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
        toneGenerator = generator

        BEEP_START_OFFSETS_MS.forEach { offsetMs ->
            handler.postDelayed(
                { generator.startTone(ToneGenerator.TONE_PROP_BEEP2, BEEP_DURATION_MS) },
                offsetMs,
            )
        }
        handler.postDelayed(
            {
                if (toneGenerator === generator) {
                    generator.release()
                    toneGenerator = null
                }
            },
            PATTERN_DURATION_MS,
        )
    }

    internal companion object {
        fun beepStartOffsetsMs(): List<Long> = BEEP_START_OFFSETS_MS.toList()

        private const val BEEP_DURATION_MS = 100
        private const val PATTERN_DURATION_MS = 1_260L
        private val BEEP_START_OFFSETS_MS = longArrayOf(0L, 180L, 360L, 800L, 980L, 1_160L)
    }
}
