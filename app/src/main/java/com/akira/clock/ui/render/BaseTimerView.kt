package com.akira.clock.ui.render

import android.content.Context
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.akira.clock.R

abstract class BaseTimerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr), TimerRenderer {
    protected val primaryColor: Int = context.getColor(R.color.clock_primary)
    protected val foregroundColor: Int = context.getColor(R.color.clock_on_surface)
    protected val mutedColor: Int = context.getColor(R.color.clock_on_surface_muted)
    protected val outlineColor: Int = context.getColor(R.color.clock_outline)

    protected var state: TimerUiState? = null
        private set
    protected var timeText: String = "00:00"
        private set
    protected var stageText: String = ""
        private set

    protected fun textPaint(
        color: Int,
        sizeSp: Float,
        align: Paint.Align = Paint.Align.CENTER,
    ): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            sizeSp,
            resources.displayMetrics,
        )
        textAlign = align
        typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL)
    }

    final override fun bind(state: TimerUiState) {
        this.state = state
        timeText = TimerRenderMath.formatRemaining(state.remainingMs)
        stageText = if (state.paused) "已暂停" else TimerRenderMath.stageLabel(state.stage)
        contentDescription = TimerRenderMath.accessibilityDescription(state)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        onStateBound(state)
        invalidate()
    }

    protected open fun onStateBound(state: TimerUiState) = Unit

    override fun startRendering() = Unit

    override fun stopRendering() = Unit
}
