package com.akira.clock.ui.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.min

/**
 * Draws time strings as seven-segment LCD digits in Casio-style.
 *
 * Segments are sharp-cornered rectangles whose verticals overlap the horizontals at every joint,
 * producing a compact, connected digital-watch look. All seven segments are displayed faintly
 * (ghost segments) with active segments drawn at full opacity on top, matching authentic Casio
 * LCD watch aesthetics. Colons remain always on. Only digits and ':' are supported; the time text
 * from [TimerRenderMath] is always one of those.
 */
internal object SegmentDigits {
    private val SEGMENTS: Map<Char, String> = mapOf(
        '0' to "abcdef",
        '1' to "bc",
        '2' to "abged",
        '3' to "abgcd",
        '4' to "fgbc",
        '5' to "afgcd",
        '6' to "afgecd",
        '7' to "abc",
        '8' to "abcdefg",
        '9' to "abcdfg",
    )

    private const val DIGIT_WIDTH_RATIO = 0.58f
    private const val THICKNESS_RATIO = 0.11f
    private const val GAP_RATIO = 0.16f
    private const val COLON_WIDTH_RATIO = 0.30f

    fun draw(
        canvas: Canvas,
        text: String,
        centerX: Float,
        centerY: Float,
        color: Int,
        maxWidth: Float,
        maxHeight: Float,
        colonOn: Boolean,
        paint: Paint,
    ) {
        if (text.isEmpty()) return
        val height = resolveHeight(text, maxWidth, maxHeight)
        val width = height * DIGIT_WIDTH_RATIO
        val thickness = height * THICKNESS_RATIO
        val gap = height * GAP_RATIO
        val colonWidth = height * COLON_WIDTH_RATIO
        val totalWidth = measureWidth(text, width, gap, colonWidth)
        var x = centerX - totalWidth * 0.5f
        val top = centerY - height * 0.5f
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.alpha = 255
        for (ch in text) {
            if (ch == ':') {
                drawColon(canvas, x, top, colonWidth, height, thickness, colonOn, paint)
                x += colonWidth + gap
            } else {
                drawDigit(canvas, x, top, width, height, thickness, SEGMENTS[ch] ?: "", paint)
                x += width + gap
            }
        }
    }

    private fun resolveHeight(text: String, maxWidth: Float, maxHeight: Float): Float {
        val colonCount = text.count { it == ':' }
        val digitCount = text.length - colonCount
        val widthPerHeight = digitCount * DIGIT_WIDTH_RATIO +
            (text.length - 1) * GAP_RATIO +
            colonCount * (COLON_WIDTH_RATIO - GAP_RATIO)
        val fromWidth = if (widthPerHeight > 0f) maxWidth / widthPerHeight else maxHeight
        return min(fromWidth, maxHeight)
    }

    private fun measureWidth(text: String, width: Float, gap: Float, colonWidth: Float): Float {
        var total = 0f
        for (ch in text) {
            total += if (ch == ':') colonWidth else width
            total += gap
        }
        return (total - gap).coerceAtLeast(0f)
    }

    private fun drawDigit(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        t: Float,
        active: String,
        paint: Paint,
    ) {
        val midTop = y + (h - t) * 0.5f
        val midBottom = midTop + t
        val allSegments = "abcdefg"
        paint.alpha = 35
        for (seg in allSegments) {
            val rect = when (seg) {
                'a' -> RectF(x, y, x + w, y + t)
                'g' -> RectF(x, midTop, x + w, midBottom)
                'd' -> RectF(x, y + h - t, x + w, y + h)
                'f' -> RectF(x, y, x + t, midBottom)
                'b' -> RectF(x + w - t, y, x + w, midBottom)
                'e' -> RectF(x, midTop, x + t, y + h)
                'c' -> RectF(x + w - t, midTop, x + w, y + h)
                else -> null
            } ?: continue
            canvas.drawRect(rect, paint)
        }
        paint.alpha = 255
        for (seg in active) {
            val rect = when (seg) {
                'a' -> RectF(x, y, x + w, y + t)
                'g' -> RectF(x, midTop, x + w, midBottom)
                'd' -> RectF(x, y + h - t, x + w, y + h)
                'f' -> RectF(x, y, x + t, midBottom)
                'b' -> RectF(x + w - t, y, x + w, midBottom)
                'e' -> RectF(x, midTop, x + t, y + h)
                'c' -> RectF(x + w - t, midTop, x + w, y + h)
                else -> null
            } ?: continue
            canvas.drawRect(rect, paint)
        }
    }

    private fun drawColon(
        canvas: Canvas,
        x: Float,
        y: Float,
        colonWidth: Float,
        h: Float,
        t: Float,
        on: Boolean,
        paint: Paint,
    ) {
        val cx = x + colonWidth * 0.5f
        val radius = t * 0.6f
        paint.alpha = 35
        canvas.drawCircle(cx, y + h * 0.34f, radius, paint)
        canvas.drawCircle(cx, y + h * 0.66f, radius, paint)
        paint.alpha = 255
        canvas.drawCircle(cx, y + h * 0.34f, radius, paint)
        canvas.drawCircle(cx, y + h * 0.66f, radius, paint)
    }
}
