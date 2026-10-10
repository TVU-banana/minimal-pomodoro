package com.akira.clock.ui

import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.akira.clock.R

/** Applies the app's shared surface and accessible action color after an alert dialog is shown. */
internal fun AlertDialog.applyClockDialogTheme() {
    window?.setBackgroundDrawableResource(R.drawable.bg_poster_preview)
    val primary = ContextCompat.getColor(context, R.color.clock_primary)
    listOf(
        AlertDialog.BUTTON_NEGATIVE,
        AlertDialog.BUTTON_NEUTRAL,
        AlertDialog.BUTTON_POSITIVE,
    ).forEach { which ->
        getButton(which)?.setTextColor(primary)
    }
}
