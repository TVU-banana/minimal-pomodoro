package com.akira.clock.ui.render

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.akira.clock.data.TimerStyle
import java.io.File

/**
 * Owns renderer View creation and visibility-bound animation control.
 *
 * The Fragment supplies immutable [TimerUiState] values. This controller never reads Room and never
 * calculates countdown time.
 */
internal class TimerRendererController(
    private val context: Context,
    private val host: FrameLayout,
    private val reduceMotion: Boolean,
) {
    private var renderer: TimerRenderer? = null
    private var rendering = false

    init {
        host.clipToOutline = true
    }

    fun show(style: TimerStyle, backgroundFile: File?) {
        renderer?.stopRendering()
        host.removeAllViews()
        addBackground(backgroundFile)

        val timerView = when (style) {
            TimerStyle.DIGITAL -> DigitalTimerView(context)
            TimerStyle.RING -> RingTimerView(context)
            TimerStyle.HOURGLASS -> HourglassTimerView(context).apply {
                this.reduceMotion = this@TimerRendererController.reduceMotion
            }
        }
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

    private fun addBackground(file: File?) {
        if (file?.isFile != true) return
        host.addView(ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageURI(Uri.fromFile(file))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = matchParentLayoutParams()
        })
        host.addView(View(context).apply {
            val nightMode = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            setBackgroundColor(if (nightMode) DARK_OVERLAY else LIGHT_OVERLAY)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = matchParentLayoutParams()
        })
    }

    private fun matchParentLayoutParams() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private companion object {
        val DARK_OVERLAY = 0x99000000.toInt()
        val LIGHT_OVERLAY = 0x99FFFFFF.toInt()
    }
}
