package com.akira.clock.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.akira.clock.R

/** Circular theme toggle that shows the current mode with a sun or moon icon. */
internal class ThemeToggleButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val sun = requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_theme_sun))
    private val moon = requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_theme_moon))
    private var dark = false
    private var onThemeChanged: ((Boolean) -> Unit)? = null

    init {
        isFocusable = true
        isClickable = true
    }

    fun bind(isDark: Boolean, onChanged: (Boolean) -> Unit) {
        dark = isDark
        onThemeChanged = onChanged
        updateSemantics()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val iconSize = (w * 20 / 48).coerceAtLeast(1)
        val left = (w - iconSize) / 2
        val top = (h - iconSize) / 2
        sun.setBounds(left, top, left + iconSize, top + iconSize)
        moon.setBounds(left, top, left + iconSize, top + iconSize)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        (if (dark) moon else sun).draw(canvas)
    }

    override fun performClick(): Boolean {
        if (!isEnabled) return false
        dark = !dark
        updateSemantics()
        invalidate()
        super.performClick()
        onThemeChanged?.invoke(dark)
        return true
    }

    private fun updateSemantics() {
        val iconColor = context.getColor(R.color.clock_on_surface_muted)
        sun.setTint(iconColor)
        moon.setTint(iconColor)
        ViewCompat.setStateDescription(
            this,
            context.getString(
                if (dark) R.string.settings_theme_dark else R.string.settings_theme_light,
            ),
        )
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.ToggleButton"
        info.isCheckable = true
        info.isChecked = dark
    }

    override fun onDetachedFromWindow() {
        onThemeChanged = null
        super.onDetachedFromWindow()
    }
}
