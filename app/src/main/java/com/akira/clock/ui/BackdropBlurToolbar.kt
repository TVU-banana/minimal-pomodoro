package com.akira.clock.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.akira.clock.R
import kotlin.math.ceil

/** Draws a blurred snapshot of the scrolling content beneath the translucent toolbar. */
internal class BackdropBlurToolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val blurRadius = 24f * resources.displayMetrics.density
    private val blurPadding = ceil(blurRadius * 3).toInt()
    private val sourceLocation = IntArray(2)
    private val toolbarLocation = IntArray(2)
    private val backdropDrawable = BackdropGlassDrawable(
        context, blurRadius / CAPTURE_SCALE, blurPadding,
    )
    private val sourceScrollListener = View.OnScrollChangeListener { _, _, _, _, _ ->
        scheduleCapture()
    }
    private val sourceLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        scheduleCapture()
    }

    private var backdropSource: View? = null
    private var backdropBitmap: Bitmap? = null
    private var captureCanvas: Canvas? = null
    private var capturePosted = false
    private var observingSource = false

    init {
        background = backdropDrawable
    }

    /** Keeps the toolbar backdrop aligned with the timer content that scrolls behind it. */
    fun bindBackdrop(source: View) {
        if (backdropSource === source) {
            scheduleCapture()
            return
        }
        stopObservingSource()
        backdropSource = source
        startObservingSource()
        scheduleCapture()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startObservingSource()
        scheduleCapture()
    }

    override fun onDetachedFromWindow() {
        stopObservingSource()
        capturePosted = false
        releaseBackdrop()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        scheduleCapture()
    }

    private fun startObservingSource() {
        val source = backdropSource ?: return
        if (!isAttachedToWindow || observingSource) return
        source.setOnScrollChangeListener(sourceScrollListener)
        source.addOnLayoutChangeListener(sourceLayoutListener)
        observingSource = true
    }

    private fun stopObservingSource() {
        val source = backdropSource ?: return
        if (!observingSource) return
        source.setOnScrollChangeListener(null)
        source.removeOnLayoutChangeListener(sourceLayoutListener)
        observingSource = false
    }

    private fun scheduleCapture() {
        if (!isAttachedToWindow || capturePosted) return
        capturePosted = true
        postOnAnimation {
            capturePosted = false
            captureBackdrop()
        }
    }

    private fun captureBackdrop() {
        val source = backdropSource ?: return
        if (!isAttachedToWindow || !isShown || width <= 0 || height <= 0) return

        // Blur removes fine detail; a quarter-size capture needs only 1/16 of the pixels.
        val bitmapWidth = ceil(width / CAPTURE_SCALE).toInt()
        val bitmapHeight = ceil((height + blurPadding * 2) / CAPTURE_SCALE).toInt()
        val currentBitmap = backdropBitmap
        if (currentBitmap == null || currentBitmap.width != bitmapWidth ||
            currentBitmap.height != bitmapHeight
        ) {
            releaseBackdrop()
            backdropBitmap = Bitmap.createBitmap(
                bitmapWidth,
                bitmapHeight,
                Bitmap.Config.ARGB_8888,
            )
            captureCanvas = Canvas(requireNotNull(backdropBitmap))
        }

        val bitmap = requireNotNull(backdropBitmap)
        val canvas = requireNotNull(captureCanvas)
        canvas.drawColor(context.getColor(R.color.clock_background))
        source.getLocationOnScreen(sourceLocation)
        getLocationOnScreen(toolbarLocation)
        canvas.save()
        canvas.scale(1f / CAPTURE_SCALE, 1f / CAPTURE_SCALE)
        canvas.translate(
            (sourceLocation[0] - toolbarLocation[0]).toFloat(),
            (sourceLocation[1] - toolbarLocation[1] + blurPadding - source.paddingTop)
                .toFloat(),
        )
        source.draw(canvas)
        canvas.restore()
        backdropDrawable.setBackdrop(bitmap)
        invalidate()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE) scheduleCapture()
    }

    private fun releaseBackdrop() {
        val bitmap = backdropBitmap
        backdropBitmap = null
        captureCanvas = null
        backdropDrawable.setBackdrop(null)
        bitmap?.recycle()
    }

    private companion object {
        const val CAPTURE_SCALE = 4f
    }
}

/** Draws the sampled backdrop during the normal background pass, below toolbar child controls. */
private class BackdropGlassDrawable(
    context: Context,
    private val blurRadius: Float,
    private val blurPadding: Int,
) : Drawable() {
    private val glass = requireNotNull(
        ContextCompat.getDrawable(context, R.drawable.bg_timer_toolbar_glass),
    )
    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val blurEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP)
    } else {
        null
    }
    private val blurNode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        RenderNode("toolbar-backdrop").apply { setRenderEffect(blurEffect) }
    } else {
        null
    }
    private var backdrop: Bitmap? = null
    private var fallbackBackdrop: Bitmap? = null

    fun setBackdrop(bitmap: Bitmap?) {
        fallbackBackdrop?.recycle()
        fallbackBackdrop = null
        backdrop = bitmap
        if (bitmap != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                blurNode?.let { node ->
                    node.setPosition(0, 0, bitmap.width, bitmap.height)
                    val recordingCanvas = node.beginRecording(bitmap.width, bitmap.height)
                    recordingCanvas.drawBitmap(bitmap, 0f, 0f, null)
                    node.endRecording()
                }
            }
            if (blurNode == null) fallbackBackdrop = createFallbackBlur(bitmap)
        }
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        backdrop?.let { bitmap ->
            val node = blurNode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                node != null && canvas.isHardwareAccelerated
            ) {
                canvas.save()
                canvas.translate(bounds.left.toFloat(), bounds.top - blurPadding.toFloat())
                canvas.scale(
                    bounds.width().toFloat() / bitmap.width,
                    (bounds.height() + blurPadding * 2).toFloat() / bitmap.height,
                )
                canvas.drawRenderNode(node)
                canvas.restore()
            } else {
                val displayedBitmap = fallbackBackdrop ?: bitmap
                canvas.drawBitmap(
                    displayedBitmap,
                    Rect(0, 0, displayedBitmap.width, displayedBitmap.height),
                    RectF(
                        bounds.left.toFloat(),
                        bounds.top - blurPadding.toFloat(),
                        bounds.right.toFloat(),
                        bounds.bottom + blurPadding.toFloat(),
                    ),
                    blurPaint,
                )
            }
        }
        glass.bounds = bounds
        glass.draw(canvas)
    }

    override fun getOutline(outline: Outline) {
        outline.setRect(bounds)
    }

    override fun setAlpha(alpha: Int) {
        blurPaint.alpha = alpha
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blurNode?.alpha = alpha / 255f
        }
        glass.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        blurPaint.colorFilter = colorFilter
        glass.colorFilter = colorFilter
        invalidateSelf()
    }

    private fun createFallbackBlur(bitmap: Bitmap): Bitmap {
        val width = (bitmap.width / FALLBACK_DOWNSAMPLE).coerceAtLeast(1)
        val height = (bitmap.height / FALLBACK_DOWNSAMPLE).coerceAtLeast(1)
        val reduced = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val values = IntArray(width * height)
        reduced.getPixels(values, 0, width, 0, 0, width, height)
        if (reduced !== bitmap) reduced.recycle()

        val firstPass = IntArray(values.size)
        val secondPass = IntArray(values.size)
        var source = values
        val radius = (blurRadius / FALLBACK_DOWNSAMPLE).toInt().coerceAtLeast(1)
        repeat(FALLBACK_BLUR_PASSES) {
            boxBlur(source, firstPass, width, height, radius, horizontal = true)
            boxBlur(firstPass, secondPass, width, height, radius, horizontal = false)
            source = secondPass
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(source, 0, width, 0, 0, width, height)
        }
    }

    private fun boxBlur(
        source: IntArray,
        destination: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        horizontal: Boolean,
    ) {
        val windowSize = radius * 2 + 1
        val lineCount = if (horizontal) height else width
        val lineLength = if (horizontal) width else height
        for (line in 0 until lineCount) {
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -radius..radius) {
                val coordinate = offset.coerceIn(0, lineLength - 1)
                val index = if (horizontal) {
                    line * width + coordinate
                } else {
                    coordinate * width + line
                }
                val color = source[index]
                alpha += color ushr 24
                red += color ushr 16 and 0xFF
                green += color ushr 8 and 0xFF
                blue += color and 0xFF
            }
            for (position in 0 until lineLength) {
                val index = if (horizontal) {
                    line * width + position
                } else {
                    position * width + line
                }
                destination[index] = Color.argb(
                    alpha / windowSize,
                    red / windowSize,
                    green / windowSize,
                    blue / windowSize,
                )
                val leaving = if (horizontal) {
                    line * width + (position - radius).coerceIn(0, width - 1)
                } else {
                    (position - radius).coerceIn(0, height - 1) * width + line
                }
                val entering = if (horizontal) {
                    line * width + (position + radius + 1).coerceIn(0, width - 1)
                } else {
                    (position + radius + 1).coerceIn(0, height - 1) * width + line
                }
                val oldColor = source[leaving]
                val newColor = source[entering]
                alpha += (newColor ushr 24) - (oldColor ushr 24)
                red += (newColor ushr 16 and 0xFF) - (oldColor ushr 16 and 0xFF)
                green += (newColor ushr 8 and 0xFF) - (oldColor ushr 8 and 0xFF)
                blue += (newColor and 0xFF) - (oldColor and 0xFF)
            }
        }
    }

    @Deprecated("Drawable opacity is no longer used by the rendering pipeline")
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val FALLBACK_DOWNSAMPLE = 6
        const val FALLBACK_BLUR_PASSES = 3
    }
}
