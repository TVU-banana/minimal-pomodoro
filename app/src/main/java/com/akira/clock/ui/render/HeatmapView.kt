package com.akira.clock.ui.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.akira.clock.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Theme-aware activity heatmap showing focus duration over time.
 *
 * Weeks flow from left to right and days are stacked Sunday-first. The view fits a fixed
 * three-month page without horizontal scrolling and keeps future days visually absent.
 */
internal class HeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val squareSize = (12 * density).toInt()
    private val squareGap = (3 * density).toInt()
    private val cornerRadius = (3 * density).toFloat()
    private val monthFormatter = DateTimeFormatter.ofPattern(
        context.getString(R.string.heatmap_month_format),
        Locale.getDefault(),
    )

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.clock_on_surface_muted)
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics,
        )
    }

    private val weekdayLabels = listOf(
        context.getString(R.string.heatmap_weekday_sunday),
        context.getString(R.string.heatmap_weekday_monday),
        context.getString(R.string.heatmap_weekday_tuesday),
        context.getString(R.string.heatmap_weekday_wednesday),
        context.getString(R.string.heatmap_weekday_thursday),
        context.getString(R.string.heatmap_weekday_friday),
        context.getString(R.string.heatmap_weekday_saturday),
    )
    private val labelColumnWidth = ceil(
        weekdayLabels.maxOf { labelPaint.measureText(it) } + 8f * density,
    ).toInt()
    private val rowStride = max(
        squareSize + squareGap,
        ceil(labelPaint.descent() - labelPaint.ascent() + 4f * density).toInt(),
    )
    private val monthLabelHeight = ceil(
        labelPaint.descent() - labelPaint.ascent() + 12f * density,
    ).toInt()
    private val cellBounds = RectF()

    private val colorEmpty = ContextCompat.getColor(context, R.color.clock_outline_soft)
    private val colorLevel1 = ContextCompat.getColor(context, R.color.heatmap_level_1)
    private val colorLevel2 = ContextCompat.getColor(context, R.color.heatmap_level_2)
    private val colorLevel3 = ContextCompat.getColor(context, R.color.heatmap_level_3)
    private val colorLevel4 = ContextCompat.getColor(context, R.color.heatmap_level_4)
    private val squarePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // data: map of "dayIndex" (0-based from range start) to focus minutes
    private var data: Map<Int, Int> = emptyMap()
    private var rangeStart = LocalDate.now()
    private var totalDays = 1
    private var lastVisibleDate = rangeStart

    /** Updates the displayed date range and daily focus totals. */
    fun setData(
        activityData: Map<Int, Int>,
        start: LocalDate,
        days: Int,
        visibleThrough: LocalDate,
    ) {
        data = activityData
        rangeStart = start
        totalDays = days.coerceAtLeast(1)
        lastVisibleDate = visibleThrough
        contentDescription = context.getString(
            R.string.heatmap_accessibility,
            start.toString(), start.plusDays(totalDays.toLong() - 1).toString(),
            activityData.values.sum(),
        )
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val columns = HeatmapGrid.columnCount(rangeStart, totalDays)
        val desiredWidth = labelColumnWidth + columns * (squareSize + squareGap) - squareGap
        val contentHeight = 7 * rowStride + monthLabelHeight
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(contentHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val columns = HeatmapGrid.columnCount(rangeStart, totalDays)
        val cellSize = min(
            squareSize,
            ((width - labelColumnWidth - squareGap * (columns - 1)) / columns).coerceAtLeast(1),
        )
        val gridWidth = columns * (cellSize + squareGap) - squareGap
        val availableWidth = (width - labelColumnWidth).coerceAtLeast(gridWidth)
        val gridStartX = labelColumnWidth + (availableWidth - gridWidth) / 2
        val monthLabelBaseline = 7f * rowStride - labelPaint.ascent() + 4f * density
        weekdayLabels.forEachIndexed { row, label ->
            val centerY = row * rowStride + cellSize / 2f
            canvas.drawText(
                label,
                0f,
                centerY - (labelPaint.ascent() + labelPaint.descent()) / 2f,
                labelPaint,
            )
        }
        for (dayIndex in 0 until totalDays) {
            val position = HeatmapGrid.positionFor(rangeStart, dayIndex)
            val weekIndex = position.column
            val dayOfWeek = position.row
            val date = rangeStart.plusDays(dayIndex.toLong())

            val x = gridStartX + weekIndex * (cellSize + squareGap)
            val y = dayOfWeek * rowStride

            if (HeatmapGrid.showsMonthLabel(rangeStart, dayIndex)) {
                canvas.drawText(
                    monthFormatter.format(date),
                    x.toFloat(),
                    monthLabelBaseline.toFloat(),
                    labelPaint,
                )
            }

            if (date.isAfter(lastVisibleDate)) continue

            val minutes = data[dayIndex] ?: 0
            val color = getColorForMinutes(minutes)
            squarePaint.color = color
            cellBounds.set(
                x.toFloat(), y.toFloat(), (x + cellSize).toFloat(), (y + cellSize).toFloat(),
            )
            canvas.drawRoundRect(cellBounds, cornerRadius, cornerRadius, squarePaint)
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
