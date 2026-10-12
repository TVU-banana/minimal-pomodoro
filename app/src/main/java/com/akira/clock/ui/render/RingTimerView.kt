package com.akira.clock.ui.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import androidx.core.content.res.ResourcesCompat
import com.akira.clock.R
import kotlin.math.min

/**
 * Theme-aware phase ring with a licensed, fixed-width LCD font and no digit backdrop.
 *
 * Text fitting happens only when state or size changes. Drawing never reads persistence, formats
 * strings, or creates graphics objects, so foreground ticks cannot add work to the timer lifecycle.
 * The ring always represents phase progress; stopwatch mode retains its elapsed-time semantics.
 */
internal class RingTimerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : BaseTimerView(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val backgroundColor = context.getColor(R.color.clock_background)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.clock_outline_soft)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 4f * density
    }
    private val progressPaint = Paint(trackPaint).apply { color = primaryColor }
    private val digitPaint = textPaint(foregroundColor, 84f).apply {
        typeface = requireNotNull(ResourcesCompat.getFont(context, R.font.dseg7_classic_regular))
        textAlign = Paint.Align.LEFT
    }
    private val digitInkBounds = Rect()
    private var digitCenterOffset = 0f
    private val taskPaint = TextPaint(textPaint(foregroundColor, 18f)).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val bounds = RectF()
    private var taskLabel = ""

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitText()
    }

    override fun onStateBound(state: TimerUiState) {
        fitText()
    }

    private fun fitText() {
        if (width == 0 || height == 0) return
        taskLabel = TextUtils.ellipsize(
            state?.taskName.orEmpty(), taskPaint,
            (width * if (width > height) 0.5f else 1f) - 48f * density,
            TextUtils.TruncateAt.END,
        ).toString()
        val diameter = ringDiameter()
        digitPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 84f, resources.displayMetrics,
        )
        val measured = digitPaint.measureText(timeText)
        if (measured > diameter * 0.88f) {
            digitPaint.textSize *= diameter * 0.88f / measured
        }
        // Without inactive electrodes, leading "1" needs optical centering of the visible ink.
        digitPaint.getTextBounds(timeText, 0, timeText.length, digitInkBounds)
        digitCenterOffset = digitInkBounds.exactCenterX()
    }

    private fun ringDiameter(): Float = if (width > height) {
        min(width * 0.5f - 48f * density, height - 48f * density).coerceAtLeast(0f)
    } else {
        min(width - 48f * density, height - 168f * density).coerceAtLeast(0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(backgroundColor)
        val diameter = ringDiameter()
        val landscape = width > height
        val centerX = width * if (landscape) 0.25f else 0.5f
        // Landscape keeps the dial and the control dock in separate columns.
        val centerY = if (landscape) height * 0.5f else {
            (height - 112f * density) * 0.5f + 20f * density
        }
        bounds.set(
            centerX - diameter * 0.5f, centerY - diameter * 0.5f,
            centerX + diameter * 0.5f, centerY + diameter * 0.5f,
        )
        canvas.drawArc(bounds, START_ANGLE_DEG, FULL_SWEEP_ANGLE_DEG, false, trackPaint)
        canvas.drawArc(
            bounds,
            START_ANGLE_DEG,
            FULL_SWEEP_ANGLE_DEG * (state?.boundedPhaseProgress ?: 0f),
            false,
            progressPaint,
        )
        val baseline = centerY - (digitPaint.ascent() + digitPaint.descent()) * 0.5f
        canvas.drawText(timeText, centerX - digitCenterOffset, baseline, digitPaint)
        val taskX = if (landscape) width * 0.75f else centerX
        val taskY = if (landscape) height * 0.28f else {
            (bounds.top - 28f * density).coerceAtLeast(taskPaint.textSize + 12f * density)
        }
        canvas.drawText(taskLabel, taskX, taskY, taskPaint)
    }

    private companion object {
        private const val START_ANGLE_DEG = -90f
        private const val FULL_SWEEP_ANGLE_DEG = 360f
    }
}
