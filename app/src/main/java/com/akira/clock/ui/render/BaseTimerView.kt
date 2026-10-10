package com.akira.clock.ui.render

import android.content.Context
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.akira.clock.R
import com.akira.clock.timer.TimerStage

/** Base View that applies shared timer text, colors, and accessibility semantics. */
internal abstract class BaseTimerView @JvmOverloads constructor(
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
        stageText = context.getString(stageLabelResource(state.stage))
        contentDescription = if (state.categoryName.isBlank()) {
            context.getString(
                if (state.stopwatch) R.string.timer_elapsed_accessibility_without_category else {
                    R.string.timer_accessibility_without_category
                },
                state.taskName,
                stageText,
                timeText,
            )
        } else {
            context.getString(
                if (state.stopwatch) R.string.timer_elapsed_accessibility_with_category else {
                    R.string.timer_accessibility_with_category
                },
                state.taskName,
                state.categoryName,
                stageText,
                timeText,
            )
        }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        onStateBound(state)
        invalidate()
    }

    protected open fun onStateBound(state: TimerUiState) = Unit

    override fun startRendering() = Unit

    override fun stopRendering() = Unit

    private fun stageLabelResource(stage: TimerStage): Int = when (stage) {
        TimerStage.Idle -> R.string.timer_stage_idle
        TimerStage.Warmup -> R.string.timer_stage_warmup
        TimerStage.Focus -> R.string.timer_stage_focus
        TimerStage.Paused -> R.string.timer_stage_paused
        TimerStage.Completed -> R.string.timer_stage_completed
        TimerStage.Aborted -> R.string.timer_stage_aborted
    }
}
