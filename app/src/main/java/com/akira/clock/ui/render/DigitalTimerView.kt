package com.akira.clock.ui.render

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet

class DigitalTimerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : BaseTimerView(context, attrs, defStyleAttr) {
    private val timePaint = textPaint(foregroundColor, 64f).apply {
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val stagePaint = textPaint(mutedColor, 16f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width * 0.5f
        val centerY = height * 0.5f
        val baseline = centerY - (timePaint.ascent() + timePaint.descent()) * 0.5f
        canvas.drawText(timeText, centerX, baseline, timePaint)
        canvas.drawText(stageText, centerX, baseline + 40f * resources.displayMetrics.density, stagePaint)
    }

}
