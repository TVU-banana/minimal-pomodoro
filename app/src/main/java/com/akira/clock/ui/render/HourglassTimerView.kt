package com.akira.clock.ui.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.provider.Settings
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.akira.clock.timer.TimerStage
import kotlin.math.max
import kotlin.math.min

internal class HourglassTimerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : BaseTimerView(context, attrs, defStyleAttr), Choreographer.FrameCallback {
    private val density = resources.displayMetrics.density
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = outlineColor
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val sandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = primaryColor
        style = Paint.Style.FILL
    }
    private val timePaint = textPaint(foregroundColor, 32f).apply {
        typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
    }
    private val stagePaint = textPaint(mutedColor, 13f)
    private val glassPath = Path()
    private val sandPath = Path()
    private val particlesX = FloatArray(PARTICLE_COUNT)
    private val particlesPhase = FloatArray(PARTICLE_COUNT)
    private var renderingRequested = false
    private var framePosted = false
    private var lastFrameNanos = 0L
    private var reducedMotionOverride: Boolean? = null
    private var systemMotionEnabled = true

    init {
        for (index in particlesX.indices) {
            particlesX[index] = pseudoRandom(index * 17 + 3)
            particlesPhase[index] = pseudoRandom(index * 29 + 11)
        }
    }

    var reduceMotion: Boolean
        get() = (reducedMotionOverride ?: false) || !systemMotionEnabled
        set(value) {
            reducedMotionOverride = value
            updateFrameLoop()
            invalidate()
        }

    fun useSystemMotionPreference() {
        reducedMotionOverride = null
        systemMotionEnabled = readSystemAnimationsEnabled()
        updateFrameLoop()
    }

    override fun startRendering() {
        renderingRequested = true
        systemMotionEnabled = readSystemAnimationsEnabled()
        updateFrameLoop()
    }

    override fun stopRendering() {
        renderingRequested = false
        removeFrame()
    }

    override fun onStateBound(state: TimerUiState) {
        updateFrameLoop()
    }

    override fun doFrame(frameTimeNanos: Long) {
        framePosted = false
        if (!shouldAnimate()) return
        if (frameTimeNanos - lastFrameNanos >= FRAME_INTERVAL_NANOS) {
            lastFrameNanos = frameTimeNanos
            invalidate()
        }
        postFrame()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val glassWidth = min(width * 0.58f, 260f * density)
        val glassHeight = min(height * 0.60f, 330f * density)
        val centerX = width * 0.5f
        val top = max(16f * density, (height - glassHeight) * 0.36f)
        val bottom = top + glassHeight
        val left = centerX - glassWidth * 0.5f
        val right = centerX + glassWidth * 0.5f
        val neckTop = top + glassHeight * 0.47f
        val neckBottom = top + glassHeight * 0.53f

        glassPath.rewind()
        glassPath.moveTo(left, top)
        glassPath.lineTo(right, top)
        glassPath.lineTo(centerX + 5f * density, neckTop)
        glassPath.lineTo(centerX + 5f * density, neckBottom)
        glassPath.lineTo(right, bottom)
        glassPath.lineTo(left, bottom)
        glassPath.lineTo(centerX - 5f * density, neckBottom)
        glassPath.lineTo(centerX - 5f * density, neckTop)
        glassPath.close()
        canvas.drawPath(glassPath, glassPaint)

        val progress = state?.boundedPhaseProgress ?: 0f
        drawSand(canvas, left, right, top, bottom, centerX, glassHeight, progress)
        if (shouldAnimate()) drawParticles(canvas, centerX, neckTop, neckBottom, bottom)

        val timeBaseline = bottom + 36f * density
        canvas.drawText(timeText, centerX, timeBaseline, timePaint)
        canvas.drawText(stageText, centerX, timeBaseline + 26f * density, stagePaint)
    }

    private fun drawSand(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        centerX: Float,
        glassHeight: Float,
        progress: Float,
    ) {
        val remaining = 1f - progress
        val upperDepth = glassHeight * 0.40f * remaining
        val lowerDepth = glassHeight * 0.40f * progress
        sandPath.rewind()
        if (upperDepth > 1f) {
            sandPath.moveTo(left + 9f * density, top + 8f * density)
            sandPath.lineTo(right - 9f * density, top + 8f * density)
            sandPath.lineTo(centerX, top + 8f * density + upperDepth)
            sandPath.close()
            canvas.drawPath(sandPath, sandPaint)
        }
        if (lowerDepth > 1f) {
            sandPath.rewind()
            sandPath.moveTo(left + 9f * density, bottom - 8f * density)
            sandPath.lineTo(right - 9f * density, bottom - 8f * density)
            sandPath.lineTo(centerX, bottom - 8f * density - lowerDepth)
            sandPath.close()
            canvas.drawPath(sandPath, sandPaint)
        }
    }

    private fun drawParticles(
        canvas: Canvas,
        centerX: Float,
        neckTop: Float,
        neckBottom: Float,
        bottom: Float,
    ) {
        val timeSeconds = (System.nanoTime() % 1_000_000_000L) / 1_000_000_000f
        val travel = (bottom - neckBottom) * 0.78f
        for (index in particlesX.indices) {
            val phase = (timeSeconds + particlesPhase[index]) % 1f
            val x = centerX + (particlesX[index] - 0.5f) * 5f * density
            val y = neckTop + (neckBottom - neckTop) * 0.5f + phase * travel
            canvas.drawCircle(x, y, 1.35f * density, sandPaint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        systemMotionEnabled = readSystemAnimationsEnabled()
        updateFrameLoop()
    }

    override fun onDetachedFromWindow() {
        removeFrame()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateFrameLoop()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateFrameLoop()
    }

    private fun updateFrameLoop() {
        if (shouldAnimate()) postFrame() else removeFrame()
    }

    private fun shouldAnimate(): Boolean =
        renderingRequested &&
            !reduceMotion &&
            state?.paused == false &&
            (state?.stage == TimerStage.Warmup || state?.stage == TimerStage.Focus) &&
            isAttachedToWindow &&
            isShown &&
            windowVisibility == VISIBLE

    private fun postFrame() {
        if (!framePosted) {
            framePosted = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun removeFrame() {
        if (framePosted) {
            Choreographer.getInstance().removeFrameCallback(this)
            framePosted = false
        }
    }

    private fun readSystemAnimationsEnabled(): Boolean = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }.getOrDefault(true)

    private fun pseudoRandom(seed: Int): Float {
        val value = (seed * 1_103_515_245L + 12_345L) and 0x7fffffff
        return value.toFloat() / 0x7fffffff.toFloat()
    }

    private companion object {
        const val PARTICLE_COUNT = 24
        const val FRAME_INTERVAL_NANOS = 33_333_333L
    }
}
