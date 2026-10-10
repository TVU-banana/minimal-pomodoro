package com.akira.clock.ui

import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.akira.clock.ClockApplication
import com.akira.clock.R
import com.akira.clock.data.HabitEntity
import com.akira.clock.habit.HabitPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Retains one accepted save across configuration changes without retaining view references. */
internal class HabitEditorSaveState : ViewModel() {
    var save: Deferred<Result<Unit>>? = null
}

/** Keeps validation and save feedback in the editor; Room owns successful changes. */
internal class HabitEditorDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val content = layoutInflater.inflate(R.layout.dialog_habit_editor, null)
        val input = content.findViewById<EditText>(R.id.habit_name_input)
        val noteInput = content.findViewById<EditText>(R.id.habit_note_input)
        val targetInput = content.findViewById<EditText>(R.id.habit_target_input)
        val id = arguments?.getString(ARG_ID)
        val state = ViewModelProvider(this)[HabitEditorSaveState::class.java]
        if (savedInstanceState == null) {
            input.setText(arguments?.getString(ARG_NAME).orEmpty())
            noteInput.setText(arguments?.getString(ARG_NOTE).orEmpty())
            targetInput.setText((arguments?.getInt(ARG_TARGET, 1) ?: 1).toString())
        }
        content.findViewById<android.view.View>(R.id.habit_target_minus).setOnClickListener {
            val count = targetInput.text.toString().toIntOrNull() ?: 1
            targetInput.setText((count - 1).coerceIn(1, HabitPolicy.MAX_TARGET_COUNT).toString())
        }
        content.findViewById<android.view.View>(R.id.habit_target_plus).setOnClickListener {
            val count = targetInput.text.toString().toIntOrNull() ?: 1
            targetInput.setText((count + 1).coerceIn(1, HabitPolicy.MAX_TARGET_COUNT).toString())
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(if (id == null) R.string.habit_new else R.string.habit_edit_title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.habit_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.applyClockDialogTheme()
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            if (state.save != null) observeSave(dialog, input, state)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (state.save != null) return@setOnClickListener
                val name = input.text.toString().trim()
                val target = targetInput.text.toString().toIntOrNull()
                if (target == null || target !in 1..HabitPolicy.MAX_TARGET_COUNT) {
                    targetInput.error = getString(R.string.habit_target_invalid)
                    targetInput.requestFocus()
                    return@setOnClickListener
                }
                if (name.isEmpty()) {
                    input.error = getString(R.string.habit_name_required)
                    input.requestFocus()
                } else {
                    val note = noteInput.text.toString()
                    save.isEnabled = false
                    val app = requireActivity().application as ClockApplication
                    state.save = app.habitCommandScope.async {
                        try {
                            val repository = app.habits
                            if (id == null) {
                                repository.create(name, note, target)
                            } else {
                                repository.update(id, name, note, target)
                            }
                            Result.success(Unit)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            Result.failure(failure)
                        }
                    }
                    observeSave(dialog, input, state)
                }
            }
        }
        return dialog
    }

    private fun observeSave(
        dialog: AlertDialog,
        input: EditText,
        state: HabitEditorSaveState,
    ) {
        val pending = state.save ?: return
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        lifecycleScope.launch {
            if (pending.await().isSuccess) {
                dismissAllowingStateLoss()
            } else {
                state.save = null
                input.error = getString(R.string.habit_operation_failed)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
            }
        }
    }

    internal companion object {
        private const val ARG_ID = "habitId"
        private const val ARG_NAME = "habitName"
        private const val ARG_TARGET = "habitTarget"
        private const val ARG_NOTE = "habitNote"

        fun editing(habit: HabitEntity, targetCount: Int = 1) = HabitEditorDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_ID, habit.id)
                putString(ARG_NAME, habit.name)
                putString(ARG_NOTE, habit.note)
                putInt(ARG_TARGET, targetCount)
            }
        }
    }
}
