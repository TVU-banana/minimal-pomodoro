package com.akira.clock.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.HorizontalScrollView
import kotlin.math.min

/** Keeps a compact, centered tag row while reserving room for the time-block name. */
internal class TagChipStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : HorizontalScrollView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val maximum = min((120 * resources.displayMetrics.density).toInt(), available / 2)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(maximum, MeasureSpec.AT_MOST),
            heightMeasureSpec,
        )
    }
}
