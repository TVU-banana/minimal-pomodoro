package com.akira.clock.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.akira.clock.R
import kotlin.math.roundToInt

/** Single-capsule daily progress indicator; a click opens the existing Today detail screen. */
internal class TodayProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.clock_toolbar_glass)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.clock_primary)
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.clock_on_surface_muted)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics,
        )
    }
    private val labelMetrics = Paint.FontMetrics()
    private val unfilledTextColor = context.getColor(R.color.clock_on_surface_muted)
    private val filledTextColor = context.getColor(R.color.clock_on_primary)
    private val trackBounds = RectF()
    private val fillBounds = RectF()
    private val fillClip = Path()
    private var fraction = 0f
    private var percentLabel = ""
    private var textBaseline = 0f

    init { bind(TodayProgress(0, 0)) }

    fun bind(progress: TodayProgress) {
        val minutes = progress.creditedMs / 60_000L
        val goal = progress.goalMs / 60_000L
        fraction = progress.fraction
        percentLabel = context.getString(
            R.string.today_progress_percent, (fraction * 100).roundToInt(),
        )
        contentDescription = if (goal > 0) {
            context.getString(R.string.today_progress_accessibility, minutes, goal)
        } else {
            context.getString(R.string.today_progress_no_goal_accessibility, minutes)
        }
        updateGeometry()
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateGeometry()
    }

    private fun updateGeometry() {
        if (width <= 0 || height <= 0) return
        val horizontalInset = density
        val verticalInset = 9f * density
        trackBounds.set(
            horizontalInset,
            verticalInset,
            width - horizontalInset,
            height - verticalInset,
        )
        val contentInset = 2f * density
        val contentWidth = (trackBounds.width() - contentInset * 2).coerceAtLeast(0f)
        fillBounds.set(
            trackBounds.left + contentInset,
            trackBounds.top + contentInset,
            trackBounds.left + contentInset + contentWidth * fraction,
            trackBounds.bottom - contentInset,
        )
        fillClip.rewind()
        if (fraction > 0f) {
            val fillRadius = fillBounds.height() * 0.5f
            fillClip.addRoundRect(fillBounds, fillRadius, fillRadius, Path.Direction.CW)
        }
        label.getFontMetrics(labelMetrics)
        textBaseline = trackBounds.centerY() - (labelMetrics.ascent + labelMetrics.descent) * 0.5f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (trackBounds.isEmpty) return
        val radius = trackBounds.height() * 0.5f
        canvas.drawRoundRect(trackBounds, radius, radius, track)
        if (fraction > 0f) canvas.drawPath(fillClip, fill)
        canvas.drawRoundRect(trackBounds, radius, radius, outline)
        label.color = unfilledTextColor
        canvas.drawText(percentLabel, trackBounds.centerX(), textBaseline, label)
        if (fraction > 0f) {
            // The same glyphs switch contrast exactly where the rounded fill passes underneath.
            val checkpoint = canvas.save()
            canvas.clipPath(fillClip)
            label.color = filledTextColor
            canvas.drawText(percentLabel, trackBounds.centerX(), textBaseline, label)
            canvas.restoreToCount(checkpoint)
        }
    }
}
