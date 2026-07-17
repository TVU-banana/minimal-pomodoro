package com.akira.clock.ui.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import kotlin.math.min

internal class RingTimerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : BaseTimerView(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val outerTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = outlineColor
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeWidth = 6f * density
    }
    private val outerProgressPaint = Paint(outerTrackPaint).apply { color = primaryColor }
    private val innerTrackPaint = Paint(outerTrackPaint).apply { strokeWidth = 3f * density }
    private val innerProgressPaint = Paint(innerTrackPaint).apply {
        color = context.getColor(com.akira.clock.R.color.clock_accent)
    }
    private val timePaint = textPaint(foregroundColor, 40f).apply {
        typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
    }
    private val stagePaint = textPaint(mutedColor, 13f)
    private val outerBounds = RectF()
    private val innerBounds = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val diameter = (min(width, height) - 40f * density).coerceAtLeast(0f)
        val left = (width - diameter) * 0.5f
        val top = (height - diameter) * 0.5f
        outerBounds.set(left, top, left + diameter, top + diameter)
        val innerInset = 18f * density
        innerBounds.set(
            left + innerInset,
            top + innerInset,
            left + diameter - innerInset,
            top + diameter - innerInset,
        )
        canvas.drawArc(outerBounds, -90f, 360f, false, outerTrackPaint)
        canvas.drawArc(
            outerBounds,
            -90f,
            360f * (state?.boundedTotalProgress ?: 0f),
            false,
            outerProgressPaint,
        )
        canvas.drawArc(innerBounds, -90f, 360f, false, innerTrackPaint)
        canvas.drawArc(
            innerBounds,
            -90f,
            360f * (state?.boundedPhaseProgress ?: 0f),
            false,
            innerProgressPaint,
        )

        val centerX = width * 0.5f
        val centerY = height * 0.5f
        val timeBaseline = centerY - (timePaint.ascent() + timePaint.descent()) * 0.5f
        canvas.drawText(timeText, centerX, timeBaseline, timePaint)
        canvas.drawText(stageText, centerX, timeBaseline + 32f * density, stagePaint)
    }
}
