package com.akira.clock.ui.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.akira.clock.R

/**
 * Vertical bar chart that uses a dense layout for 24 hours and fills the viewport for seven days.
 * Designed to be placed inside a HorizontalScrollView.
 * - Bars grow bottom-to-top.
 * - Labels at the bottom, values at the top.
 */
internal class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val cornerRadius = (3 * density).toFloat()
    private val chartHeight = (160 * density).toInt()
    private val topPad = (28 * density).toInt()
    private val bottomPad = (32 * density).toInt()
    private val sidePad = (8 * density).toInt()
    private val weeklyBarGap = (8 * density).toInt()

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.clock_on_surface_muted)
        textSize = 10f * density
        textAlign = Paint.Align.CENTER
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.clock_on_surface)
        textSize = 10f * density
        textAlign = Paint.Align.CENTER
    }

    private val backgroundPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.clock_outline_soft)
    }

    private val primaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.clock_primary)
    }

    data class TimeBarItem(
        val label: String,
        val valueMs: Long,
    )

    private var timeItems: List<TimeBarItem> = emptyList()
    private var viewportWidth = 0

    fun setTimeData(barItems: List<TimeBarItem>) {
        timeItems = barItems
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val count = timeItems.size.coerceAtLeast(1)
        val layout = layoutFor(count)
        val totalWidth = layout.totalWidth
        val totalHeight = topPad + chartHeight + bottomPad
        setMeasuredDimension(totalWidth, totalHeight)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post {
            val width = (parent as? View)?.width ?: 0
            if (width > 0 && width != viewportWidth) {
                viewportWidth = width
                requestLayout()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (timeItems.isEmpty()) return

        val maxVal = timeItems.maxOfOrNull { it.valueMs }?.coerceAtLeast(1L) ?: 1L
        val barTop = topPad.toFloat()
        val barBottom = (topPad + chartHeight).toFloat()
        val layout = layoutFor(timeItems.size)

        timeItems.forEachIndexed { index, item ->
            val x = layout.sidePad + index * (layout.barWidth + layout.barGap)
            val cx = x + layout.barWidth / 2f

            // Background track
            val bgRect = RectF(x.toFloat(), barTop, (x + layout.barWidth).toFloat(), barBottom)
            canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, backgroundPaint)

            // Filled bar (grows from bottom)
            val fraction = item.valueMs.toFloat() / maxVal
            val fillHeight = chartHeight * fraction
            if (fillHeight > 0) {
                val fillTop = barBottom - fillHeight
                val fillRect = RectF(x.toFloat(), fillTop, (x + layout.barWidth).toFloat(), barBottom)
                canvas.drawRoundRect(fillRect, cornerRadius, cornerRadius, primaryPaint)
            }

            // A 24-hour view needs dense bars; per-bar values would overlap and obscure the chart.
            if (item.valueMs > 0 && timeItems.size <= 12) {
                val hours = item.valueMs / 3600_000.0
                val formatted = String.format("%.1fh", hours)
                canvas.drawText(formatted, cx, barTop - (6 * density), valuePaint)
            }

            // Time label at bottom
            canvas.drawText(item.label, cx, barBottom + (16 * density), labelPaint)
        }
    }

    private fun layoutFor(count: Int): BarLayout {
        if (count == DAYS_PER_WEEK && viewportWidth > 0) {
            val available = viewportWidth - sidePad * 2 - weeklyBarGap * (count - 1)
            val barWidth = (available / count).coerceAtLeast(1)
            return BarLayout(barWidth, weeklyBarGap, sidePad, viewportWidth)
        }

        val barWidth = (12 * density).toInt()
        val barGap = (2 * density).toInt()
        val totalWidth = sidePad * 2 + count * barWidth + (count - 1) * barGap
        return BarLayout(barWidth, barGap, sidePad, totalWidth)
    }

    private data class BarLayout(
        val barWidth: Int,
        val barGap: Int,
        val sidePad: Int,
        val totalWidth: Int,
    )

    private companion object {
        const val DAYS_PER_WEEK = 7
    }
}
