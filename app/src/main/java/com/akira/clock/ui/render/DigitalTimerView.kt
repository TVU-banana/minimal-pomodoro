package com.akira.clock.ui.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet

internal class DigitalTimerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : BaseTimerView(context, attrs, defStyleAttr) {
    private val timePaint = textPaint(foregroundColor, 68f).apply {
        typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
    }
    private val stagePaint = textPaint(mutedColor, 13f)
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = primaryColor
        strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.SQUARE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width * 0.5f
        val centerY = height * 0.5f
        val baseline = centerY - (timePaint.ascent() + timePaint.descent()) * 0.5f
        canvas.drawText(timeText, centerX, baseline, timePaint)
        val density = resources.displayMetrics.density
        canvas.drawLine(centerX - 22f * density, baseline + 30f * density, centerX + 22f * density, baseline + 30f * density, accentPaint)
        canvas.drawText(stageText, centerX, baseline + 58f * density, stagePaint)
    }

}
