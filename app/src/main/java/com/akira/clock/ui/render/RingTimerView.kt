package com.akira.clock.ui.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import kotlin.math.min

/**
 * Unified countdown renderer: a single large progress ring with serif font digits at its center.
 *
 * The ring fills as the current phase elapses. Remaining time is formatted by [TimerRenderMath]
 * as HH:MM:SS for durations of one hour or more, otherwise MM:SS, then drawn with serif font.
 * This is the only timer visualization; the former multi-style picker and background overlay have
 * been removed so the running countdown can occupy the entire screen without distraction.
 */
internal class RingTimerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : BaseTimerView(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = outlineColor
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 14f * density
    }
    private val progressPaint = Paint(trackPaint).apply { color = primaryColor }
    private val timePaint = textPaint(primaryColor, 72f).apply {
        typeface = android.graphics.Typeface.create("times", android.graphics.Typeface.NORMAL)
    }
    private val stagePaint = textPaint(mutedColor, 15f)
    private val bounds = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val strokeInset = trackPaint.strokeWidth * 0.5f + 12f * density
        val diameter = (min(width, height) - 2f * strokeInset).coerceAtLeast(0f)
        val left = (width - diameter) * 0.5f
        val top = (height - diameter) * 0.5f
        bounds.set(left, top, left + diameter, top + diameter)
        canvas.drawArc(bounds, -90f, 360f, false, trackPaint)
        canvas.drawArc(
            bounds,
            -90f,
            360f * (state?.boundedPhaseProgress ?: 0f),
            false,
            progressPaint,
        )

        val centerX = width * 0.5f
        val centerY = height * 0.5f
        val maxTextWidth = diameter * 0.80f
        val maxTextHeight = diameter * 0.32f
        val textSize = resolveTextSize(maxTextWidth, maxTextHeight, timeText)
        timePaint.textSize = textSize
        canvas.drawText(timeText, centerX, centerY - (timePaint.ascent() + timePaint.descent()) * 0.5f, timePaint)
        val stageY = centerY + maxTextHeight * 0.5f + 24f * density
        canvas.drawText(stageText, centerX, stageY, stagePaint)
    }

    private fun resolveTextSize(maxWidth: Float, maxHeight: Float, text: String): Float {
        val testPaint = Paint(timePaint)
        var size = maxHeight
        testPaint.textSize = size
        while (testPaint.measureText(text) > maxWidth && size > 8f) {
            size *= 0.95f
            testPaint.textSize = size
        }
        return size
    }
}
