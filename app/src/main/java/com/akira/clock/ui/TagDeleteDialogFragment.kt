package com.akira.clock.ui

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.akira.clock.R

/** Confirms archival without changing session snapshots or deleting referenced Room rows. */
internal class TagDeleteDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.tag_delete_title, requireArguments().getString(ARG_NAME)))
            .setMessage(R.string.tag_delete_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.tag_delete) { _, _ ->
                val id = requireArguments().getString(ARG_ID) ?: return@setPositiveButton
                (parentFragment as? TagEditorDialogFragment.Listener)?.onTagDeleted(id)
            }
            .create().apply {
                setOnShowListener {
                    applyClockDialogTheme()
                    getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(
                        requireContext().getColor(R.color.clock_danger),
                    )
                }
            }

    companion object {
        const val DIALOG_TAG = "tagDeleteConfirmation"
        private const val ARG_ID = "tagId"
        private const val ARG_NAME = "tagName"

        fun newInstance(id: String, name: String): TagDeleteDialogFragment =
            TagDeleteDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_ID, id)
                    putString(ARG_NAME, name)
                }
            }
    }
}
