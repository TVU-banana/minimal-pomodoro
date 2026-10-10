package com.akira.clock.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.akira.clock.R
import com.akira.clock.report.FocusPosterData
import com.akira.clock.report.FocusPosterDraft
import com.akira.clock.report.PosterLayoutPlanner
import com.akira.clock.report.PosterRect
import com.akira.clock.report.ReportPeriod
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Renders a self-contained, high-resolution PNG rather than a screenshot of the report UI. */
internal class FocusPosterRenderer(context: Context) {
    private val resources = context.resources
    private val background = ContextCompat.getColor(context, R.color.poster_background)
    private val surface = ContextCompat.getColor(context, R.color.poster_surface)
    private val primary = ContextCompat.getColor(context, R.color.clock_primary)
    private val ink = ContextCompat.getColor(context, R.color.poster_on_surface)
    private val muted = ContextCompat.getColor(context, R.color.poster_on_surface_muted)
    private val outline = ContextCompat.getColor(context, R.color.poster_outline)
    private val songtiTypeface = requireNotNull(
        ResourcesCompat.getFont(context, R.font.source_han_serif_cn_extra_light_3),
    ) { "Bundled Songti font is unavailable" }

    fun render(draft: FocusPosterDraft, outputWidth: Int = WIDTH): Bitmap {
        val data = draft.data
        require(outputWidth > 0)
        val layout = PosterLayoutPlanner.plan(draft.sections)
        val bitmap = Bitmap.createBitmap(
            outputWidth,
            (outputWidth.toLong() * layout.height / WIDTH).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.scale(outputWidth.toFloat() / WIDTH, outputWidth.toFloat() / WIDTH)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.drawRoundRect(
            RectF(0f, 0f, WIDTH.toFloat(), layout.height.toFloat()),
            POSTER_CORNER_RADIUS,
            POSTER_CORNER_RADIUS,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background },
        )
        val songti = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = songtiTypeface }
        songti.apply { color = ink; textSize = 84f; textAlign = Paint.Align.LEFT }
        canvas.drawText(periodTitle(data.period), MARGIN, 150f, songti)
        songti.apply { color = muted; textSize = 40f; textAlign = Paint.Align.RIGHT }
        canvas.drawText(rangeLabel(data), WIDTH - MARGIN, 146f, songti)
        songti.apply { color = ink; textSize = 190f; textAlign = Paint.Align.LEFT }
        canvas.drawText(formatDuration(data.totalDurationMs), MARGIN, 402f, songti)

        layout.chart?.let { chart -> drawChart(canvas, data, chart, songti) }
        layout.reflection?.let { reflection ->
            drawReflection(canvas, draft.reflectionText, reflection, songti)
        }
        layout.motivationCenterY?.let { centerY ->
            songti.apply { color = muted; textSize = 48f; textAlign = Paint.Align.RIGHT }
            canvas.drawText(
                resources.getString(R.string.poster_motivation),
                WIDTH - MARGIN,
                centeredTextBaseline(songti, centerY),
                songti,
            )
        }
        return bitmap
    }

    private fun drawChart(canvas: Canvas, data: FocusPosterData, bounds: PosterRect, text: Paint) {
        val card = RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
        canvas.drawRoundRect(
            card,
            CARD_CORNER_RADIUS,
            CARD_CORNER_RADIUS,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surface },
        )
        if (data.period == ReportPeriod.DAY || data.period == ReportPeriod.WEEK) {
            drawLineChart(canvas, data, card, text)
        } else {
            drawHeatmap(canvas, data, card, text)
        }
    }

    private fun drawLineChart(
        canvas: Canvas,
        data: FocusPosterData,
        card: RectF,
        text: Paint,
    ) {
        val left = card.left + 56f
        val right = card.right - 44f
        val top = card.top + 170f
        val bottom = card.bottom - 130f
        val max = data.values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
        val count = data.values.size
        val path = Path()
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = outline; strokeWidth = 2f }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primary
            style = Paint.Style.STROKE
            strokeWidth = 5f
            strokeJoin = Paint.Join.ROUND
        }
        for (step in 0..2) {
            val y = top + (bottom - top) * step / 2f
            canvas.drawLine(left, y, right, y, grid)
        }
        data.values.forEachIndexed { index, value ->
            val x = left + (right - left) * index / (count - 1).coerceAtLeast(1)
            val y = bottom - (bottom - top) * value.toFloat() / max
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            if (count == 7 || index == 0 || index == count - 1 || index % 4 == 0) {
                text.apply { color = muted; textSize = 24f; textAlign = Paint.Align.CENTER }
                val label = if (count == 7) {
                    resources.getString(R.string.chart_weekday_labels).split(",")[index]
                } else {
                    String.format(Locale.US, "%02d", index)
                }
                canvas.drawText(label, x, bottom + 62f, text)
            }
        }
        canvas.drawPath(path, line)
        text.apply { color = ink; textSize = 34f; textAlign = Paint.Align.LEFT }
        canvas.drawText(
            resources.getString(
                if (count == 7) R.string.poster_chart_daily else R.string.poster_chart_hourly,
            ),
            left,
            card.top + 110f,
            text,
        )
    }

    private fun drawHeatmap(
        canvas: Canvas,
        data: FocusPosterData,
        card: RectF,
        text: Paint,
    ) {
        val days = data.values.size.coerceAtLeast(1)
        val columns = ((days + data.rangeStart.dayOfWeek.value % 7 + 6) / 7).coerceAtLeast(1)
        val cell = minOf(60f, (card.width() - 120f) / columns - 5f).coerceAtLeast(3f)
        val gap = minOf(6f, cell / 3f)
        val startX = card.left + 86f
        val startY = card.top + 200f
        val max = data.values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
        text.apply { color = ink; textSize = 34f; textAlign = Paint.Align.LEFT }
        canvas.drawText(
            resources.getString(R.string.poster_chart_intensity),
            card.left + 56f,
            card.top + 130f,
            text,
        )
        data.values.forEachIndexed { index, value ->
            val absolute = data.rangeStart.dayOfWeek.value % 7 + index
            val column = absolute / 7
            val row = absolute % 7
            val alpha = if (value == 0L) {
                0x22
            } else {
                (0x55 + (0xaa * value / max).toInt()).coerceAtMost(0xff)
            }
            val color = (alpha shl 24) or (primary and 0x00ffffff)
            val x = startX + column * (cell + gap)
            val y = startY + row * (cell + gap)
            canvas.drawRoundRect(
                RectF(x, y, x + cell, y + cell),
                cell / 5,
                cell / 5,
                Paint().apply { this.color = color },
            )
        }
    }

    private fun drawReflection(canvas: Canvas, source: String, bounds: PosterRect, text: Paint) {
        val card = RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
        canvas.drawRoundRect(
            card,
            CARD_CORNER_RADIUS,
            CARD_CORNER_RADIUS,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = surface },
        )
        text.apply { color = muted; textSize = 34f; textAlign = Paint.Align.LEFT }
        canvas.drawText(
            resources.getString(R.string.poster_reflection_title),
            card.left + 56f,
            card.top + 72f,
            text,
        )
        text.apply { color = ink; textSize = 36f; textAlign = Paint.Align.LEFT }
        val lines = wrapText(source, text, card.width() - 112f)
        require(lines.size <= MAX_REFLECTION_LINES) { "Reflection does not fit in four lines" }
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, card.left + 56f, card.top + 132f + index * 48f, text)
        }
    }

    private fun wrapText(source: String, text: Paint, maxWidth: Float): List<String> = buildList {
        source.split('\n').forEach { paragraph ->
            if (paragraph.isEmpty()) {
                add("")
                return@forEach
            }
            val line = StringBuilder()
            paragraph.codePoints().forEach { codePoint ->
                val candidate = line.toString() + String(Character.toChars(codePoint))
                if (line.isNotEmpty() && text.measureText(candidate) > maxWidth) {
                    add(line.toString())
                    line.clear()
                }
                line.appendCodePoint(codePoint)
            }
            if (line.isNotEmpty()) add(line.toString())
        }
    }

    private fun centeredTextBaseline(paint: Paint, centerY: Float): Float =
        centerY - (paint.ascent() + paint.descent()) / 2f

    private fun periodTitle(period: ReportPeriod): String = resources.getString(
        when (period) {
            ReportPeriod.DAY -> R.string.poster_title_day
            ReportPeriod.WEEK -> R.string.poster_title_week
            ReportPeriod.YEAR -> R.string.poster_title_year
        },
    )

    private fun rangeLabel(data: FocusPosterData): String = if (data.period == ReportPeriod.DAY) {
        data.rangeStart.format(DateTimeFormatter.ISO_LOCAL_DATE)
    } else {
        "${data.rangeStart} — ${data.rangeEndExclusive.minusDays(1)}"
    }

    private fun formatDuration(milliseconds: Long): String {
        val minutes = milliseconds / 60_000
        return if (minutes >= 60) {
            val hours = minutes / 60
            val remainingMinutes = minutes % 60
            if (remainingMinutes == 0L) "${hours}h" else "${hours}h ${remainingMinutes}min"
        } else {
            "${minutes}min"
        }
    }

    private companion object {
        const val WIDTH = 1440
        const val MARGIN = 112f
        const val POSTER_CORNER_RADIUS = 48f
        const val CARD_CORNER_RADIUS = 32f
        const val MAX_REFLECTION_LINES = 4
    }
}
