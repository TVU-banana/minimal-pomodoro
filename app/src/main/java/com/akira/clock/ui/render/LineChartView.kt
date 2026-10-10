package com.akira.clock.ui.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.akira.clock.R

/** Fits the entire time range in the viewport; paths are rebuilt only when data or size changes. */
internal class LineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    /**
     * Discrete time series sample with human-readable X-axis label and duration in milliseconds.
     */
    internal data class TimePoint(val label: String, val valueMs: Long)

    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.clock_primary)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.clock_outline_soft)
        strokeWidth = density
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.clock_on_surface_muted)
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics,
        )
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private var points = emptyList<TimePoint>()
    private var maximumLabel = ""
    private val left get() = 24f * density
    private val right get() = width - 24f * density
    private val top get() = 28f * density
    private val bottom get() = height - 32f * density

    fun setTimeData(values: List<TimePoint>) {
        points = values
        val maxMinutes = (values.maxOfOrNull { it.valueMs } ?: 0L) / 60_000L
        maximumLabel = context.getString(R.string.duration_short_minutes, maxMinutes)
        contentDescription = values.joinToString("; ") {
            context.getString(R.string.chart_point_accessibility, it.label, it.valueMs / 60_000L)
        }
        rebuildPath()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize((220f * density).toInt(), heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuildPath()

    private fun x(index: Int): Float =
        left + (right - left) * index / (points.size - 1).coerceAtLeast(1)

    private fun rebuildPath() {
        path.reset()
        val maximum = points.maxOfOrNull { it.valueMs }?.coerceAtLeast(1L) ?: 1L
        points.forEachIndexed { index, point ->
            val y = bottom - (bottom - top) * point.valueMs.toFloat() / maximum
            if (index == 0) path.moveTo(x(index), y) else path.lineTo(x(index), y)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (step in 0..2) {
            val y = top + (bottom - top) * step / 2f
            canvas.drawLine(left, y, right, y, grid)
        }
        canvas.drawText(
            maximumLabel, left + text.measureText(maximumLabel) / 2, top - 8 * density, text,
        )
        canvas.drawPath(path, line)
        val stride = if (points.size > 12) 4 else 1
        points.forEachIndexed { index, point ->
            if (index % stride == 0 || index == points.lastIndex) {
                canvas.drawText(point.label, x(index), bottom + 22f * density, text)
            }
        }
    }
}
