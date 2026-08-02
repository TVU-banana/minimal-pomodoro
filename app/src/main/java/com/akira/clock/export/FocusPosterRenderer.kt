package com.akira.clock.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import com.akira.clock.R
import com.akira.clock.report.FocusPosterData
import com.akira.clock.report.ReportPeriod
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Renders a self-contained, high-resolution PNG rather than a screenshot of the report UI. */
internal class FocusPosterRenderer(context: Context) {
    private val background = ContextCompat.getColor(context, R.color.clock_background)
    private val surface = ContextCompat.getColor(context, R.color.clock_surface_raised)
    private val primary = ContextCompat.getColor(context, R.color.clock_primary)
    private val ink = ContextCompat.getColor(context, R.color.clock_on_surface)
    private val muted = ContextCompat.getColor(context, R.color.clock_on_surface_muted)
    private val outline = ContextCompat.getColor(context, R.color.clock_outline_soft)

    fun render(data: FocusPosterData): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        val serif = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL)
        }
        val sans = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        }
        serif.apply { color = ink; textSize = 76f }
        canvas.drawText("CLOCK / FOCUS", MARGIN, 132f, serif)
        sans.apply { color = muted; textSize = 30f; letterSpacing = 0.08f }
        canvas.drawText(periodTitle(data.period), MARGIN, 196f, sans)
        serif.apply { color = ink; textSize = 190f }
        canvas.drawText(formatDuration(data.totalFocusMs), MARGIN, 416f, serif)
        sans.apply { color = muted; textSize = 28f }
        canvas.drawText(rangeLabel(data), MARGIN, 474f, sans)

        canvas.drawRoundRect(RectF(MARGIN, 556f, WIDTH - MARGIN, 1450f), 12f, 12f, Paint().apply { color = surface })
        if (data.period == ReportPeriod.DAY || data.period == ReportPeriod.WEEK) {
            drawBars(canvas, data, sans)
        } else {
            drawHeatmap(canvas, data, sans)
        }
        sans.apply { color = muted; textSize = 25f; letterSpacing = 0f }
        canvas.drawText("记录的是趋势，不是负担。", MARGIN, 1662f, sans)
        canvas.drawText("Clock · 本地生成", MARGIN, 1722f, sans)
        return bitmap
    }

    private fun drawBars(canvas: Canvas, data: FocusPosterData, text: Paint) {
        val left = MARGIN + 56f
        val right = WIDTH - MARGIN - 44f
        val top = 690f
        val bottom = 1280f
        val max = data.values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
        val count = data.values.size
        val gap = if (count == 24) 8f else 24f
        val barWidth = (right - left - gap * (count - 1)) / count
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = outline }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primary }
        data.values.forEachIndexed { index, value ->
            val x = left + index * (barWidth + gap)
            canvas.drawRoundRect(RectF(x, top, x + barWidth, bottom), 5f, 5f, track)
            val y = bottom - (bottom - top) * value.toFloat() / max
            if (value > 0) canvas.drawRoundRect(RectF(x, y, x + barWidth, bottom), 5f, 5f, fill)
            if (count == 7 || index % 3 == 0) {
                text.apply { color = muted; textSize = 24f; textAlign = Paint.Align.CENTER }
                val label = if (count == 7) "一二三四五六日"[index].toString() else "%02d".format(index)
                canvas.drawText(label, x + barWidth / 2, 1342f, text)
            }
        }
        text.apply { color = ink; textSize = 34f; textAlign = Paint.Align.LEFT }
        canvas.drawText(if (count == 7) "每日专注时长" else "每小时专注分布", MARGIN + 56f, 630f, text)
    }

    private fun drawHeatmap(canvas: Canvas, data: FocusPosterData, text: Paint) {
        val days = data.values.size.coerceAtLeast(1)
        val columns = ((days + data.rangeStart.dayOfWeek.value % 7 + 6) / 7).coerceAtLeast(1)
        val cell = minOf(60f, (WIDTH - MARGIN * 2 - 120f) / columns - 5f).coerceAtLeast(3f)
        val gap = minOf(6f, cell / 3f)
        val startX = MARGIN + 86f
        val startY = 720f
        val max = data.values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
        text.apply { color = ink; textSize = 34f; textAlign = Paint.Align.LEFT }
        canvas.drawText("每日专注强度", MARGIN + 56f, 650f, text)
        data.values.forEachIndexed { index, value ->
            val absolute = data.rangeStart.dayOfWeek.value % 7 + index
            val column = absolute / 7
            val row = absolute % 7
            val alpha = if (value == 0L) 0x22 else (0x55 + (0xaa * value / max).toInt()).coerceAtMost(0xff)
            val color = (alpha shl 24) or (primary and 0x00ffffff)
            val x = startX + column * (cell + gap)
            val y = startY + row * (cell + gap)
            canvas.drawRoundRect(RectF(x, y, x + cell, y + cell), cell / 5, cell / 5, Paint().apply { this.color = color })
        }
        text.apply { color = muted; textSize = 24f }
        canvas.drawText("低", MARGIN + 56f, 1360f, text)
        canvas.drawText("高", WIDTH - MARGIN - 76f, 1360f, text)
    }

    private fun periodTitle(period: ReportPeriod): String = when (period) {
        ReportPeriod.DAY -> "本日专注"
        ReportPeriod.WEEK -> "本周专注"
        ReportPeriod.MONTH -> "本月专注"
        ReportPeriod.YEAR -> "本年度专注"
        ReportPeriod.ALL_TIME -> "有史以来"
    }

    private fun rangeLabel(data: FocusPosterData): String = if (data.period == ReportPeriod.DAY) {
        data.rangeStart.format(DateTimeFormatter.ISO_LOCAL_DATE)
    } else {
        "${data.rangeStart} — ${data.rangeEndExclusive.minusDays(1)}"
    }

    private fun formatDuration(milliseconds: Long): String {
        val minutes = milliseconds / 60_000
        return if (minutes >= 60) "%d小时%02d分".format(minutes / 60, minutes % 60) else "${minutes}分"
    }

    private companion object {
        const val WIDTH = 1440
        const val HEIGHT = 1800
        const val MARGIN = 112f
    }
}
