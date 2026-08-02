package com.akira.clock.ui

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.akira.clock.R

/** Creates or edits a preset (time block) under a category. */
internal class PresetEditorDialogFragment : DialogFragment() {

    interface Listener {
        fun onPresetCreated(name: String, totalDurationMs: Long, warmupDurationMs: Long)
        fun onPresetUpdated(id: String, name: String, totalDurationMs: Long, warmupDurationMs: Long)
        fun onPresetDeleted(id: String)
    }

    private var editingPresetId: String? = null
    private var nameInput: EditText? = null
    private var totalInput: EditText? = null
    private var warmupInput: EditText? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_preset_editor, null)
        nameInput = view.findViewById(R.id.preset_name_input)
        totalInput = view.findViewById(R.id.preset_total_input)
        warmupInput = view.findViewById(R.id.preset_warmup_input)

        val args = arguments
        val isEditing = args != null && args.containsKey(ARG_PRESET_ID)
        if (isEditing) {
            editingPresetId = args?.getString(ARG_PRESET_ID)
            nameInput?.setText(args?.getString(ARG_PRESET_NAME))
            totalInput?.setText(((args?.getLong(ARG_PRESET_TOTAL_MS, 60 * 60_000L) ?: 60 * 60_000L) / 60_000L).toString())
            warmupInput?.setText(((args?.getLong(ARG_PRESET_WARMUP_MS, 15 * 60_000L) ?: 15 * 60_000L) / 60_000L).toString())
        } else {
            totalInput?.setText("60")
            warmupInput?.setText("15")
        }

        val dialogBuilder = AlertDialog.Builder(context)
            .setTitle(if (isEditing) R.string.preset_edit else R.string.preset_new)
            .setView(view)
            .setPositiveButton(if (isEditing) R.string.tag_save else R.string.tag_create, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (isEditing) {
            dialogBuilder.setNeutralButton(R.string.tag_delete) { _, _ -> dispatchDelete() }
        }
        val dialog = dialogBuilder.create()
        dialog.setOnShowListener {
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
        val total = totalInput?.text?.toString()?.toIntOrNull()
        val warmup = warmupInput?.text?.toString()?.toIntOrNull()
        if (total == null || warmup == null || total !in 1..1_440 || warmup !in 0 until total) {
            totalInput?.error = getString(R.string.timer_invalid_duration)
            return
        }
        (parentFragment as? Listener)?.onPresetCreated(name, total * 60_000L, warmup * 60_000L)
        dismiss()
    }

    private fun dispatchUpdate() {
        val id = editingPresetId ?: return
        val name = nameInput?.text?.toString().orEmpty().trim()
        if (name.isBlank()) {
            nameInput?.error = getString(R.string.tag_name_required)
            return
        }
        val total = totalInput?.text?.toString()?.toIntOrNull()
        val warmup = warmupInput?.text?.toString()?.toIntOrNull()
        if (total == null || warmup == null || total !in 1..1_440 || warmup !in 0 until total) {
            totalInput?.error = getString(R.string.timer_invalid_duration)
            return
        }
        (parentFragment as? Listener)?.onPresetUpdated(id, name, total * 60_000L, warmup * 60_000L)
        dismiss()
    }

    private fun dispatchDelete() {
        val id = editingPresetId ?: return
        (parentFragment as? Listener)?.onPresetDeleted(id)
        dismiss()
    }

    companion object {
        private const val ARG_PRESET_ID = "presetId"
        private const val ARG_PRESET_NAME = "presetName"
        private const val ARG_PRESET_TOTAL_MS = "presetTotalMs"
        private const val ARG_PRESET_WARMUP_MS = "presetWarmupMs"

        fun newInstance(
            presetId: String,
            name: String,
            totalDurationMs: Long,
            warmupDurationMs: Long,
        ): PresetEditorDialogFragment {
            return PresetEditorDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PRESET_ID, presetId)
                    putString(ARG_PRESET_NAME, name)
                    putLong(ARG_PRESET_TOTAL_MS, totalDurationMs)
                    putLong(ARG_PRESET_WARMUP_MS, warmupDurationMs)
                }
            }
        }
    }
}
