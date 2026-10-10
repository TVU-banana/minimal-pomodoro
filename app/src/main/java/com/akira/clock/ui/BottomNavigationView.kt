package com.akira.clock.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.akira.clock.R

/**
 * Moves one shared highlight behind the selected destination without changing touch bounds.
 * Geometry follows measured children, including RTL and scaled text. Relayout snaps to the new
 * bounds, while user selection animates only when system animations are enabled.
 * This decoration never schedules or gates destination changes.
 */
internal class BottomNavigationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val density = resources.displayMetrics.density
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.clock_primary_soft)
    }
    private val highlight = RectF()
    private val target = RectF()
    private val start = RectF()
    private val measured = RectF()
    private val easing = DecelerateInterpolator()
    private lateinit var items: LinearLayout
    private var selectedId = View.NO_ID
    private var animator: ValueAnimator? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        items = findViewById(R.id.bottom_nav_items)
        for (index in 0 until items.childCount) {
            items.getChildAt(index).accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfo,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Button"
                }
            }
        }
    }

    fun selectItem(id: Int) {
        animator?.cancel()
        animator = null
        val changed = selectedId != id
        selectedId = id
        for (index in 0 until items.childCount) {
            items.getChildAt(index).apply { isSelected = this.id == id }
        }
        positionHighlight(changed && !highlight.isEmpty)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        positionHighlight(false)
    }

    private fun positionHighlight(animate: Boolean) {
        val item = items.findViewById<View>(selectedId) ?: return
        if (item.width == 0 || item.height == 0) return
        val horizontalInset = 6f * density
        val verticalInset = 2f * density
        measured.set(
            items.left + item.left + horizontalInset,
            items.top + item.top + verticalInset,
            items.left + item.right - horizontalInset,
            items.top + item.bottom - verticalInset,
        )
        if (measured == target && !highlight.isEmpty &&
            (animator != null || highlight == target)
        ) return
        animator?.cancel()
        animator = null
        target.set(measured)
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            highlight.set(target)
            invalidate()
            return
        }
        start.set(highlight)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180L
            interpolator = easing
            addUpdateListener {
                val fraction = it.animatedFraction
                highlight.set(
                    start.left + (target.left - start.left) * fraction,
                    start.top + (target.top - start.top) * fraction,
                    start.right + (target.right - start.right) * fraction,
                    start.bottom + (target.bottom - start.bottom) * fraction,
                )
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    animator = null
                }
            })
            start()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!highlight.isEmpty) {
            val radius = 14f * density
            canvas.drawRoundRect(highlight, radius, radius, highlightPaint)
        }
        super.dispatchDraw(canvas)
    }

    /** Stops the decorative animation when its window is rebuilt or detached. */
    fun cancelSelection() {
        animator?.cancel()
        animator = null
        highlight.set(target)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelSelection()
        super.onDetachedFromWindow()
    }
}
