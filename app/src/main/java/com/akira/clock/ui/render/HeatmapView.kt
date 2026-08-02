package com.akira.clock.ui.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.akira.clock.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * GitHub-style heatmap view showing focus duration over time.
 * Color intensity is based on minutes of focus per day.
 */
internal class HeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val squareSize = (12 * density).toInt()
    private val squareGap = (3 * density).toInt()
    private val cornerRadius = (2 * density).toFloat()
    private val labelColumnWidth = (32 * density).toInt()
    private val gridTop = (20 * density).toInt()
    private val monthFormatter = DateTimeFormatter.ofPattern("M月", Locale.getDefault())

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.clock_on_surface_muted)
        textSize = 10f * density
    }

    private val primaryColorInt = ContextCompat.getColor(context, R.color.clock_primary)
    private val onPrimaryColorInt = ContextCompat.getColor(context, R.color.clock_on_primary)
    private val colorEmpty = ContextCompat.getColor(context, R.color.clock_outline_soft)
    private val colorLevel1 = (0x40 shl 24) or (primaryColorInt and 0x00FFFFFF)
    private val colorLevel2 = (0x80 shl 24) or (primaryColorInt and 0x00FFFFFF)
    private val colorLevel3 = primaryColorInt
    private val colorLevel4 = onPrimaryColorInt

    // data: map of "dayIndex" (0-based from range start) to focus minutes
    private var data: Map<Int, Int> = emptyMap()
    private var rangeStart = LocalDate.now()
    private var totalDays = 1

    /** Updates the displayed date range and daily focus totals. */
    fun setData(activityData: Map<Int, Int>, start: LocalDate, days: Int) {
        data = activityData
        rangeStart = start
        totalDays = days.coerceAtLeast(1)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val columns = HeatmapGrid.columnCount(rangeStart, totalDays)
        val contentWidth = labelColumnWidth + columns * (squareSize + squareGap) - squareGap
        val contentHeight = gridTop + 7 * (squareSize + squareGap) - squareGap
        setMeasuredDimension(contentWidth, contentHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val startX = labelColumnWidth
        val weekdayLabels = listOf(
            context.getString(R.string.heatmap_weekday_sunday),
            context.getString(R.string.heatmap_weekday_monday),
            context.getString(R.string.heatmap_weekday_tuesday),
            context.getString(R.string.heatmap_weekday_wednesday),
            context.getString(R.string.heatmap_weekday_thursday),
            context.getString(R.string.heatmap_weekday_friday),
            context.getString(R.string.heatmap_weekday_saturday),
        )
        weekdayLabels.forEachIndexed { dayOfWeek, label ->
            val y = gridTop + dayOfWeek * (squareSize + squareGap) + squareSize / 2
            canvas.drawText(
                label,
                0f,
                y - (labelPaint.ascent() + labelPaint.descent()) * 0.5f,
                labelPaint,
            )
        }

        var lastMonthLabelColumn = -3
        for (dayIndex in 0 until totalDays) {
            val position = HeatmapGrid.positionFor(rangeStart, dayIndex)
            val weekIndex = position.column
            val dayOfWeek = position.row
            val date = rangeStart.plusDays(dayIndex.toLong())

            val x = startX + weekIndex * (squareSize + squareGap)
            val y = gridTop + dayOfWeek * (squareSize + squareGap)

            if (date.dayOfMonth == 1 && weekIndex - lastMonthLabelColumn >= 3) {
                canvas.drawText(monthFormatter.format(date), x.toFloat(), labelPaint.textSize, labelPaint)
                lastMonthLabelColumn = weekIndex
            }

            val minutes = data[dayIndex] ?: 0
            val color = getColorForMinutes(minutes)
            val paint = Paint().apply { this.color = color }
            val rect = RectF(
                x.toFloat(),
                y.toFloat(),
                (x + squareSize).toFloat(),
                (y + squareSize).toFloat(),
            )
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        }
    }

    private fun getColorForMinutes(minutes: Int): Int {
        return when {
            minutes == 0 -> colorEmpty
            minutes <= 30 -> colorLevel1
            minutes <= 90 -> colorLevel2
            minutes <= 180 -> colorLevel3
            else -> colorLevel4
        }
    }
}
