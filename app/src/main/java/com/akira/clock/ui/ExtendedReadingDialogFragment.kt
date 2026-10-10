package com.akira.clock.ui

import android.app.Dialog
import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.akira.clock.R

/**
 * Extended reading about the timer's warmup rationale.
 *
 * The body carries a hyperlink rendered via [Html.fromHtml] and made clickable with
 * [LinkMovementMethod], so tapping it opens the URL in the system browser. A close button
 * dismisses the dialog.
 */
internal class ExtendedReadingDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_extended_reading, null)
        val body = view.findViewById<TextView>(R.id.reading_text)
        val html = getString(R.string.extended_reading_body) +
            "<br><br><a href=\"" + getString(R.string.extended_reading_url) + "\">" +
            getString(R.string.extended_reading_link) + "</a>"
        body.text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
        body.movementMethod = LinkMovementMethod.getInstance()
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.extended_reading_title)
            .setView(view)
            .setPositiveButton(R.string.extended_reading_close, null)
            .create()
        dialog.setOnShowListener {
            dialog.applyClockDialogTheme()
        }
        return dialog
    }
}
