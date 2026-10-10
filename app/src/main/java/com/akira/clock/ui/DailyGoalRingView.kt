package com.akira.clock.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.akira.clock.R

/** Draws the compact, theme-aware progress ring shown beside a time block's duration. */
internal class DailyGoalRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val strokeWidth = 2.5f * resources.displayMetrics.density
    private val bounds = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.clock_outline_soft)
        style = Paint.Style.STROKE
        strokeWidth = this@DailyGoalRingView.strokeWidth
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.clock_primary)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = this@DailyGoalRingView.strokeWidth
    }

    private var progressFraction = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Updates the filled arc; the adjacent percentage label carries the accessible value. */
    fun setProgress(fraction: Float) {
        progressFraction = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = (minOf(width, height) - strokeWidth) / 2f
        if (radius <= 0f) return
        val centerX = width / 2f
        val centerY = height / 2f
        bounds.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        canvas.drawArc(bounds, 0f, 360f, false, trackPaint)
        if (progressFraction > 0f) {
            canvas.drawArc(bounds, -90f, progressFraction * 360f, false, progressPaint)
        }
    }
}
