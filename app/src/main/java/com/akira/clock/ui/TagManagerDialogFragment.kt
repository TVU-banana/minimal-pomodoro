package com.akira.clock.ui

import android.app.Dialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.akira.clock.ClockApplication
import com.akira.clock.R
import com.akira.clock.data.CategoryEntity
import kotlinx.coroutines.launch

/** Offers independent tag editing and deletion; rows always follow Room. */
internal class TagManagerDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(16), dp(8))
        }
        val scroll = ScrollView(context).apply { addView(rows) }
        val app = requireActivity().application as ClockApplication
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.repository.observeTags().collect { tags ->
                    rows.removeAllViews()
                    if (tags.isEmpty()) {
                        rows.addView(TextView(context).apply {
                            setText(R.string.tag_manage_empty)
                            setTextColor(context.getColor(R.color.clock_on_surface_muted))
                            textSize = 15f
                            setPadding(0, dp(16), 0, dp(16))
                        })
                    }
                    tags.forEach { tag -> rows.addView(createRow(tag)) }
                }
            }
        }
        return AlertDialog.Builder(context)
            .setTitle(R.string.tag_manage)
            .setView(scroll)
            .setNegativeButton(R.string.tag_manage_done, null)
            .create().apply { setOnShowListener { applyClockDialogTheme() } }
    }

    private fun createRow(tag: CategoryEntity): LinearLayout {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(0, dp(8), 0, dp(8))
            addView(TextView(context).apply {
                text = tag.name
                textSize = 16f
                setTextColor(context.getColor(R.color.clock_on_surface))
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(actionButton(
                R.drawable.ic_nav_settings,
                getString(R.string.tag_edit_named, tag.name),
                R.color.clock_on_surface_muted,
            ) {
                if (parentFragmentManager.findFragmentByTag("tagEditor") == null) {
                    TagEditorDialogFragment.newInstance(
                        tag.id, tag.name, tag.colorArgb, tag.dailyGoalMs,
                    ).show(parentFragmentManager, "tagEditor")
                    dismiss()
                }
            })
            addView(actionButton(
                R.drawable.ic_delete_outline,
                getString(R.string.tag_delete_named, tag.name),
                R.color.clock_danger,
            ) {
                if (parentFragmentManager.findFragmentByTag(
                        TagDeleteDialogFragment.DIALOG_TAG,
                    ) == null
                ) {
                    TagDeleteDialogFragment.newInstance(tag.id, tag.name)
                        .show(parentFragmentManager, TagDeleteDialogFragment.DIALOG_TAG)
                }
            })
        }
    }

    private fun actionButton(
        icon: Int,
        description: String,
        color: Int,
        action: () -> Unit,
    ): ImageButton = ImageButton(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setBackgroundResource(android.R.drawable.list_selector_background)
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(context.getColor(color))
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(13), dp(13), dp(13), dp(13))
        contentDescription = description
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val DIALOG_TAG = "tagManager"
    }
}
