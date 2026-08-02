package com.akira.clock.ui.render

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Owns renderer View creation and visibility-bound animation control.
 *
 * The Fragment supplies immutable [TimerUiState] values. This controller never reads Room and never
 * calculates countdown time. It hosts a single unified ring renderer; the former style switch and
 * background-image overlay have been removed.
 */
internal class TimerRendererController(
    private val context: Context,
    private val host: FrameLayout,
) {
    private var renderer: TimerRenderer? = null
    private var rendering = false

    init {
        host.clipToOutline = true
    }

    fun show() {
        renderer?.stopRendering()
        host.removeAllViews()
        val timerView = RingTimerView(context)
        timerView.layoutParams = matchParentLayoutParams()
        host.addView(timerView)
        renderer = timerView
        if (rendering) timerView.startRendering()
    }

    fun bind(state: TimerUiState) {
        renderer?.bind(state)
    }

    fun start() {
        rendering = true
        renderer?.startRendering()
    }

    fun stop() {
        rendering = false
        renderer?.stopRendering()
    }

    private fun matchParentLayoutParams() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )
}
