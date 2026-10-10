package com.akira.clock.ui

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.akira.clock.R

/** Creates or edits a preset (time block) under a category. */
internal class PresetEditorDialogFragment : DialogFragment() {

    interface Listener {
        fun onPresetCreated(
            name: String,
            totalDurationMs: Long,
            warmupDurationMs: Long,
            stopwatch: Boolean,
            tagIds: List<String>,
            newTagName: String?,
        )
        fun onPresetUpdated(
            id: String,
            name: String,
            totalDurationMs: Long,
            warmupDurationMs: Long,
            stopwatch: Boolean,
            tagIds: List<String>,
            newTagName: String?,
        )
        fun onPresetDeleted(id: String)
    }

    private var editingPresetId: String? = null
    private var nameInput: EditText? = null
    private var totalInput: EditText? = null
    private var warmupInput: EditText? = null
    private var stopwatchInput: AppCompatCheckBox? = null
    private var newTagInput: EditText? = null
    private var tagContainer: LinearLayout? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_preset_editor, null)
        nameInput = view.findViewById(R.id.preset_name_input)
        totalInput = view.findViewById(R.id.preset_total_input)
        warmupInput = view.findViewById(R.id.preset_warmup_input)
        stopwatchInput = view.findViewById(R.id.preset_stopwatch_input)
        newTagInput = view.findViewById(R.id.preset_new_tag_input)
        tagContainer = view.findViewById(R.id.preset_tags_container)
        renderTagChoices()

        val args = arguments
        val isEditing = args != null && args.containsKey(ARG_PRESET_ID)
        if (isEditing) {
            editingPresetId = args?.getString(ARG_PRESET_ID)
            nameInput?.setText(args?.getString(ARG_PRESET_NAME))
            val defaultTotalMs = 60 * 60_000L
            val totalMs = args?.getLong(ARG_PRESET_TOTAL_MS, defaultTotalMs) ?: defaultTotalMs
            totalInput?.setText((totalMs / 60_000L).toString())
            val defaultWarmupMs = 15 * 60_000L
            val warmupMs = args?.getLong(ARG_PRESET_WARMUP_MS, defaultWarmupMs) ?: defaultWarmupMs
            warmupInput?.setText((warmupMs / 60_000L).toString())
            stopwatchInput?.isChecked = args?.getBoolean(ARG_PRESET_STOPWATCH, false) == true
        } else {
            totalInput?.setText(R.string.default_total_minutes)
            warmupInput?.setText(R.string.default_warmup_minutes)
        }
        stopwatchInput?.setOnCheckedChangeListener { _, checked ->
            setCountdownFieldsVisible(view, !checked)
        }
        setCountdownFieldsVisible(view, stopwatchInput?.isChecked != true)

        val dialogBuilder = AlertDialog.Builder(context)
            .setTitle(if (isEditing) R.string.preset_edit else R.string.preset_new)
            .setView(view)
            .setPositiveButton(if (isEditing) R.string.tag_save else R.string.tag_create, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (isEditing) {
            dialogBuilder.setNeutralButton(R.string.tag_delete) { _, _ -> dispatchDelete() }
        }
        val dialog = dialogBuilder.create()
        dialog.window?.setWindowAnimations(0)
        dialog.setOnShowListener {
            dialog.applyClockDialogTheme()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (isEditing) dispatchUpdate() else dispatchCreate()
            }
            if (isEditing) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(
                    ContextCompat.getColor(context, R.color.clock_danger)
                )
            }
        }
        return dialog
    }

    private fun dispatchCreate() {
        val name = nameInput?.text?.toString().orEmpty().trim()
        if (name.isBlank()) {
            nameInput?.error = getString(R.string.tag_name_required)
            return
        }
        val stopwatch = stopwatchInput?.isChecked == true
        val total = totalInput?.text?.toString()?.toIntOrNull()
        val warmup = warmupInput?.text?.toString()?.toIntOrNull()
        val invalidDurations = !stopwatch &&
            (total == null || warmup == null || total !in 1..1_440 || warmup !in 0..1_440)
        if (invalidDurations) {
            totalInput?.error = getString(R.string.timer_invalid_duration)
            return
        }
        val tagIds = selectedTagIds()
        if (tagIds.isEmpty() && newTagInput?.text?.isBlank() != false) {
            newTagInput?.error = getString(R.string.tag_required)
            return
        }
        (parentFragment as? Listener)?.onPresetCreated(
            name,
            (total ?: 0) * 60_000L,
            (warmup ?: 0) * 60_000L,
            stopwatch,
            tagIds,
            newTagName(),
        )
        dismiss()
    }

    private fun dispatchUpdate() {
        val id = editingPresetId ?: return
        val name = nameInput?.text?.toString().orEmpty().trim()
        if (name.isBlank()) {
            nameInput?.error = getString(R.string.tag_name_required)
            return
        }
        val stopwatch = stopwatchInput?.isChecked == true
        val total = totalInput?.text?.toString()?.toIntOrNull()
        val warmup = warmupInput?.text?.toString()?.toIntOrNull()
        val invalidDurations = !stopwatch &&
            (total == null || warmup == null || total !in 1..1_440 || warmup !in 0..1_440)
        if (invalidDurations) {
            totalInput?.error = getString(R.string.timer_invalid_duration)
            return
        }
        val tagIds = selectedTagIds()
        if (tagIds.isEmpty() && newTagInput?.text?.isBlank() != false) {
            newTagInput?.error = getString(R.string.tag_required)
            return
        }
        (parentFragment as? Listener)?.onPresetUpdated(
            id,
            name,
            (total ?: 0) * 60_000L,
            (warmup ?: 0) * 60_000L,
            stopwatch,
            tagIds,
            newTagName(),
        )
        dismiss()
    }

    private fun dispatchDelete() {
        val id = editingPresetId ?: return
        (parentFragment as? Listener)?.onPresetDeleted(id)
        dismiss()
    }

    private fun renderTagChoices() {
        val names = arguments?.getStringArrayList(ARG_TAG_NAMES).orEmpty()
        val ids = arguments?.getStringArrayList(ARG_TAG_IDS).orEmpty()
        val selectedIds = arguments?.getStringArrayList(ARG_SELECTED_TAG_IDS).orEmpty().toSet()
        ids.zip(names).forEachIndexed { index, (id, name) ->
            val choice = AppCompatCheckBox(requireContext()).apply {
                text = name
                minHeight = (48 * resources.displayMetrics.density).toInt()
                tag = id
                buttonTintList = ContextCompat.getColorStateList(
                    context,
                    R.color.clock_checkable_tint,
                )
                isChecked = id in selectedIds || (selectedIds.isEmpty() && index == 0)
            }
            tagContainer?.addView(choice)
        }
    }

    private fun selectedTagIds(): List<String> = buildList {
        for (index in 0 until (tagContainer?.childCount ?: 0)) {
            val choice = tagContainer?.getChildAt(index) as? AppCompatCheckBox ?: continue
            if (choice.isChecked) add(choice.tag as String)
        }
    }

    private fun newTagName(): String? = newTagInput?.text?.toString()?.trim()?.ifBlank { null }

    private fun setCountdownFieldsVisible(view: View, visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.preset_total_label).visibility = visibility
        totalInput?.visibility = visibility
        view.findViewById<View>(R.id.preset_warmup_label).visibility = visibility
        warmupInput?.visibility = visibility
    }

    companion object {
        private const val ARG_PRESET_ID = "presetId"
        private const val ARG_PRESET_NAME = "presetName"
        private const val ARG_PRESET_TOTAL_MS = "presetTotalMs"
        private const val ARG_PRESET_WARMUP_MS = "presetWarmupMs"
        private const val ARG_PRESET_STOPWATCH = "presetStopwatch"
        private const val ARG_TAG_IDS = "tagIds"
        private const val ARG_TAG_NAMES = "tagNames"
        private const val ARG_SELECTED_TAG_IDS = "selectedTagIds"

        fun newInstance(
            tagIds: List<String>,
            tagNames: List<String>,
            selectedTagIds: List<String> = tagIds.take(1),
        ): PresetEditorDialogFragment = PresetEditorDialogFragment().apply {
            arguments = Bundle().apply {
                putStringArrayList(ARG_TAG_IDS, ArrayList(tagIds))
                putStringArrayList(ARG_TAG_NAMES, ArrayList(tagNames))
                putStringArrayList(ARG_SELECTED_TAG_IDS, ArrayList(selectedTagIds))
            }
        }

        fun newInstance(
            presetId: String,
            name: String,
            totalDurationMs: Long,
            warmupDurationMs: Long,
            stopwatch: Boolean,
            tagIds: List<String>,
            tagNames: List<String>,
            selectedTagIds: List<String>,
        ): PresetEditorDialogFragment {
            return PresetEditorDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PRESET_ID, presetId)
                    putString(ARG_PRESET_NAME, name)
                    putLong(ARG_PRESET_TOTAL_MS, totalDurationMs)
                    putLong(ARG_PRESET_WARMUP_MS, warmupDurationMs)
                    putBoolean(ARG_PRESET_STOPWATCH, stopwatch)
                    putStringArrayList(ARG_TAG_IDS, ArrayList(tagIds))
                    putStringArrayList(ARG_TAG_NAMES, ArrayList(tagNames))
                    putStringArrayList(ARG_SELECTED_TAG_IDS, ArrayList(selectedTagIds))
                }
            }
        }

        fun newInstance(
            presetId: String,
            name: String,
            totalDurationMs: Long,
            warmupDurationMs: Long,
        ): PresetEditorDialogFragment = newInstance(
            presetId, name, totalDurationMs, warmupDurationMs, false,
            emptyList(), emptyList(), emptyList(),
        )
    }
}
