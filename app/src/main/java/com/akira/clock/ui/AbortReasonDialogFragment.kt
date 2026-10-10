package com.akira.clock.ui

import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.akira.clock.R

/**
 * Prompts for a reason when the user ends a session before the countdown finishes.
 *
 * The reason is optional: an empty input is reported as null so the user is never blocked from
 * stopping, while a typed reason is persisted on the session for later review.
 */
internal class AbortReasonDialogFragment : DialogFragment() {

    /** Implemented by the fragment that owns the timer. */
    interface Listener {
        fun onAbortConfirmed(reason: String?)
    }

    private var reasonInput: EditText? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_abort_reason, null)
        reasonInput = view.findViewById(R.id.abort_reason_input)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.abort_reason_title)
            .setView(view)
            .setPositiveButton(R.string.abort_confirm, null)
            .setNegativeButton(R.string.abort_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.applyClockDialogTheme()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val reason = reasonInput?.text?.toString().orEmpty().trim().ifBlank { null }
                (parentFragment as? Listener)?.onAbortConfirmed(reason)
                dismiss()
            }
        }
        return dialog
    }
}
