package com.akira.clock.ui

import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.fragment.app.DialogFragment
import com.akira.clock.R
import kotlin.math.roundToInt

/** Edits tag identity and daily goals independently of time-block durations. */
internal class TagEditorDialogFragment : DialogFragment() {

    /** Implemented by the fragment that owns tag creation and editing. */
    interface Listener {
        fun onTagCreated(name: String, colorArgb: Long, dailyGoalMs: Long)
        fun onTagUpdated(id: String, name: String, colorArgb: Long, dailyGoalMs: Long)
        fun onTagDeleted(id: String)
    }

    private var selectedColor: Long = TagColors.GREEN
    private var editingTagId: String? = null
    private var nameInput: EditText? = null
    private var hexInput: EditText? = null
    private var dailyGoalInput: EditText? = null
    private var preview: View? = null
    private var swatchesContainer: LinearLayout? = null
    private var addColorButton: Button? = null
    private var swatches: List<View> = emptyList()
    private var suppressHexWatch = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_tag_editor, null)
        nameInput = view.findViewById(R.id.tag_name_input)
        hexInput = view.findViewById(R.id.hex_input)
        dailyGoalInput = view.findViewById(R.id.tag_daily_goal_input)
        preview = view.findViewById(R.id.color_preview)
        swatchesContainer = view.findViewById(R.id.color_swatches_container)
        addColorButton = view.findViewById(R.id.add_color_button)
        renderSwatches()
        addColorButton?.setOnClickListener {
            val parsed = TagColors.parseHex(hexInput?.text?.toString().orEmpty())
            if (parsed != null && TagColors.addCustomColor(parsed)) {
                renderSwatches()
            }
        }
        hexInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppressHexWatch) return
                val parsed = TagColors.parseHex(s?.toString().orEmpty())
                if (parsed != null) {
                    selectedColor = parsed
                    updateSwatchSelection()
                }
                updatePreview()
            }
        })

        val args = arguments
        val isEditing = args != null && args.containsKey(ARG_TAG_ID)
        if (isEditing) {
            editingTagId = args?.getString(ARG_TAG_ID)
            nameInput?.setText(args?.getString(ARG_TAG_NAME))
            val color = args?.getLong(ARG_TAG_COLOR, TagColors.GREEN)
            if (color != null) selectColor(color)
            val defaultGoalMs = 3 * 60 * 60_000L
            val goalMs = args?.getLong(ARG_TAG_DAILY_GOAL_MS, defaultGoalMs) ?: defaultGoalMs
            dailyGoalInput?.setText((goalMs / 60_000L).toString())
        } else {
            selectColor(TagColors.GREEN)
            dailyGoalInput?.setText("180")
        }

        val dialogBuilder = AlertDialog.Builder(context)
            .setTitle(if (isEditing) R.string.tag_edit else R.string.tag_new)
            .setView(view)
            .setPositiveButton(if (isEditing) R.string.tag_save else R.string.tag_create, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (isEditing) {
            dialogBuilder.setNeutralButton(R.string.tag_delete, null)
        }
        val dialog = dialogBuilder.create()
        dialog.setOnShowListener {
            dialog.applyClockDialogTheme()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (isEditing) dispatchUpdate() else dispatchCreate()
            }
            if (isEditing) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    confirmDelete()
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(
                    ContextCompat.getColor(context, R.color.clock_danger)
                )
            }
        }
        return dialog
    }

    private fun renderSwatches() {
        swatchesContainer?.removeAllViews()
        val presets = TagColors.presets
        val density = resources.displayMetrics.density
        val swatchSize = (48 * density).toInt()
        val marginEnd = (12 * density).toInt()
        val newSwatches = mutableListOf<View>()
        presets.forEachIndexed { index, color ->
            val swatch = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(swatchSize, swatchSize).apply {
                    if (index < presets.size - 1) {
                        this.marginEnd = marginEnd
                    }
                }
                background = swatchDrawable(color, color == selectedColor)
                isFocusable = true
                isSelected = color == selectedColor
                contentDescription = getString(
                    R.string.tag_color_accessibility, TagColors.formatHex(color),
                )
                ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: View,
                        info: AccessibilityNodeInfoCompat,
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = "android.widget.RadioButton"
                        info.isCheckable = true
                        info.isChecked = host.isSelected
                    }
                })
                setOnClickListener { selectColor(color) }
            }
            swatchesContainer?.addView(swatch)
            newSwatches.add(swatch)
        }
        swatches = newSwatches
        addColorButton?.visibility = if (TagColors.hasRoomForMore()) View.VISIBLE else View.GONE
    }

    private fun selectColor(color: Long) {
        selectedColor = color
        suppressHexWatch = true
        hexInput?.setText(TagColors.formatHex(color))
        suppressHexWatch = false
        updateSwatchSelection()
        updatePreview()
    }

    private fun updateSwatchSelection() {
        renderSwatches()
    }

    private fun updatePreview() {
        val parsed = TagColors.parseHex(hexInput?.text?.toString().orEmpty())
        val color = parsed ?: selectedColor
        preview?.background = previewDrawable(color, parsed != null)
    }

    private fun dispatchCreate() {
        val name = nameInput?.text?.toString().orEmpty().trim()
        if (name.isBlank()) {
            nameInput?.error = getString(R.string.tag_name_required)
            return
        }
        val parsed = TagColors.parseHex(hexInput?.text?.toString().orEmpty())
        if (parsed == null) {
            hexInput?.error = getString(R.string.tag_color_invalid)
            return
        }
        val color = parsed
        val dailyGoal = dailyGoalInput?.text?.toString()?.toIntOrNull()
        if (dailyGoal == null || dailyGoal !in 1..1_440) {
            dailyGoalInput?.error = getString(R.string.tag_daily_goal_invalid)
            return
        }
        (parentFragment as? Listener)?.onTagCreated(name, color, dailyGoal * 60_000L)
        dismiss()
    }

    private fun dispatchUpdate() {
        val id = editingTagId ?: return
        val name = nameInput?.text?.toString().orEmpty().trim()
        if (name.isBlank()) {
            nameInput?.error = getString(R.string.tag_name_required)
            return
        }
        val parsed = TagColors.parseHex(hexInput?.text?.toString().orEmpty())
        if (parsed == null) {
            hexInput?.error = getString(R.string.tag_color_invalid)
            return
        }
        val color = parsed
        val dailyGoal = dailyGoalInput?.text?.toString()?.toIntOrNull()
        if (dailyGoal == null || dailyGoal !in 1..1_440) {
            dailyGoalInput?.error = getString(R.string.tag_daily_goal_invalid)
            return
        }
        (parentFragment as? Listener)?.onTagUpdated(id, name, color, dailyGoal * 60_000L)
        dismiss()
    }

    private fun confirmDelete() {
        val id = editingTagId ?: return
        if (parentFragmentManager.findFragmentByTag(TagDeleteDialogFragment.DIALOG_TAG) != null) {
            return
        }
        TagDeleteDialogFragment.newInstance(
            id, requireArguments().getString(ARG_TAG_NAME).orEmpty(),
        ).show(parentFragmentManager, TagDeleteDialogFragment.DIALOG_TAG)
        dismiss()
    }

    private fun swatchDrawable(color: Long, selected: Boolean): GradientDrawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color.toInt())
            setStroke(
                ((if (selected) 3f else 1f) * density).toInt(),
                ContextCompat.getColor(
                    requireContext(),
                    if (selected) R.color.clock_primary else R.color.clock_outline,
                ),
            )
        }
    }

    private fun previewDrawable(color: Long, valid: Boolean): GradientDrawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            cornerRadius = 8f * density
            setColor(color.toInt())
            setStroke(
                density.roundToInt(),
                ContextCompat.getColor(
                    requireContext(),
                    if (valid) R.color.clock_outline else R.color.clock_danger,
                ),
            )
        }
    }

    companion object {
        private const val ARG_TAG_ID = "tagId"
        private const val ARG_TAG_NAME = "tagName"
        private const val ARG_TAG_COLOR = "tagColor"
        private const val ARG_TAG_DAILY_GOAL_MS = "tagDailyGoalMs"

        fun newInstance(
            tagId: String,
            name: String,
            colorArgb: Long,
            dailyGoalMs: Long = 3 * 60 * 60_000L,
        ): TagEditorDialogFragment {
            return TagEditorDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TAG_ID, tagId)
                    putString(ARG_TAG_NAME, name)
                    putLong(ARG_TAG_COLOR, colorArgb)
                    putLong(ARG_TAG_DAILY_GOAL_MS, dailyGoalMs)
                }
            }
        }
    }
}
