package com.akira.clock.ui

import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Bitmap
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.akira.clock.R
import com.akira.clock.export.FocusPosterRenderer
import com.akira.clock.report.DailyReflectionText
import com.akira.clock.report.FocusPosterData
import com.akira.clock.report.FocusPosterDraft
import com.akira.clock.report.PosterSections
import com.akira.clock.report.ReportPeriod
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Renders bounded previews; exports alone allocate the full-resolution bitmap. */
internal class PosterCustomizeDialogFragment : DialogFragment() {
    interface Listener {
        fun onPosterDraftReady(draft: FocusPosterDraft, saveAs: Boolean)
    }

    private var preview: ImageView? = null
    private var previewBitmap: Bitmap? = null
    private var previewJob: Job? = null
    private var chartChoice: CheckBox? = null
    private var reflectionChoice: CheckBox? = null
    private var motivationChoice: CheckBox? = null
    private var reflectionInput: EditText? = null
    private var counter: TextView? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_poster_customize, null)
        chartChoice = view.findViewById(R.id.poster_chart_choice)
        reflectionChoice = view.findViewById(R.id.poster_reflection_choice)
        motivationChoice = view.findViewById(R.id.poster_motivation_choice)
        reflectionInput = view.findViewById(R.id.poster_reflection_input)
        counter = view.findViewById(R.id.poster_reflection_counter)
        preview = view.findViewById(R.id.poster_live_preview)
        bindInitialState(savedInstanceState)
        chartChoice?.setOnCheckedChangeListener { _, _ -> updatePreview() }
        motivationChoice?.setOnCheckedChangeListener { _, _ -> updatePreview() }
        reflectionChoice?.setOnCheckedChangeListener { _, checked ->
            reflectionInput?.isVisible = checked
            counter?.isVisible = checked
            updatePreview()
        }
        reflectionInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                text: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                text: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) {
                updateCounter()
                updatePreview()
            }
            override fun afterTextChanged(text: Editable?) = Unit
        })
        updateCounter()
        updatePreview()
        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.poster_preview_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.poster_save_as, null)
            .setPositiveButton(R.string.poster_save_to_gallery, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.applyClockDialogTheme()
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        submit(dialog, saveAs = true)
                    }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        submit(dialog, saveAs = false)
                    }
                }
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SHOW_CHART, chartChoice?.isChecked ?: true)
        outState.putBoolean(STATE_SHOW_REFLECTION, reflectionChoice?.isChecked ?: true)
        outState.putBoolean(STATE_SHOW_MOTIVATION, motivationChoice?.isChecked ?: true)
        outState.putString(STATE_REFLECTION_TEXT, reflectionInput?.text?.toString().orEmpty())
    }

    private fun bindInitialState(savedInstanceState: Bundle?) {
        val source = savedInstanceState ?: requireArguments()
        val restored = savedInstanceState != null
        chartChoice?.isChecked = source.getBoolean(
            if (restored) STATE_SHOW_CHART else ARG_SHOW_CHART,
            true,
        )
        reflectionChoice?.isChecked = source.getBoolean(
            if (restored) STATE_SHOW_REFLECTION else ARG_SHOW_REFLECTION,
            true,
        )
        motivationChoice?.isChecked = source.getBoolean(
            if (restored) STATE_SHOW_MOTIVATION else ARG_SHOW_MOTIVATION,
            true,
        )
        reflectionInput?.setText(source.getString(
            if (restored) STATE_REFLECTION_TEXT else ARG_REFLECTION_TEXT,
        ).orEmpty())
        reflectionInput?.isVisible = reflectionChoice?.isChecked == true
        counter?.isVisible = reflectionChoice?.isChecked == true
    }

    private fun updateCounter() {
        val text = reflectionInput?.text?.toString().orEmpty()
        counter?.text = getString(
            R.string.poster_reflection_counter,
            text.codePointCount(0, text.length),
            DailyReflectionText.MAX_CODE_POINTS,
        )
    }

    private fun submit(dialog: AlertDialog, saveAs: Boolean) {
        val reflection = DailyReflectionText.normalize(reflectionInput?.text?.toString().orEmpty())
        val sections = PosterSections(
            showChart = chartChoice?.isChecked ?: true,
            showReflection = reflectionChoice?.isChecked ?: true,
            showMotivation = motivationChoice?.isChecked ?: true,
        )
        if (sections.showReflection && !DailyReflectionText.isValid(reflection)) {
            reflectionInput?.error = getString(R.string.poster_reflection_required_or_hidden)
            return
        }
        (parentFragment as? Listener)?.onPosterDraftReady(
            FocusPosterDraft(posterData(), sections, reflection), saveAs,
        )
        dialog.dismiss()
    }

    /** Canceled renders release their bitmap before a newer draft is displayed. */
    private fun updatePreview() {
        if (!isAdded) return
        previewJob?.cancel()
        val reflection = DailyReflectionText.normalize(reflectionInput?.text?.toString().orEmpty())
        val validReflection = DailyReflectionText.isValid(reflection)
        val draft = FocusPosterDraft(
            posterData(),
            PosterSections(
                chartChoice?.isChecked == true,
                reflectionChoice?.isChecked == true,
                motivationChoice?.isChecked == true,
            ),
            if (validReflection) reflection else "",
        )
        val renderer = FocusPosterRenderer(requireContext().applicationContext)
        previewJob = lifecycleScope.launch {
            var rendered: Bitmap? = null
            try {
                withContext(Dispatchers.Default) { rendered = renderer.render(draft, 720) }
                preview?.setImageBitmap(rendered)
                previewBitmap?.recycle()
                previewBitmap = rendered
                rendered = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                reflectionInput?.error = getString(R.string.poster_reflection_required_or_hidden)
            } finally {
                rendered?.recycle()
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        previewJob?.cancel()
        preview?.setImageDrawable(null)
        preview = null
        previewBitmap?.recycle()
        previewBitmap = null
        super.onDismiss(dialog)
    }

    private fun posterData(): FocusPosterData = FocusPosterData(
        period = ReportPeriod.entries.firstOrNull {
            it.name == requireArguments().getString(ARG_PERIOD)
        } ?: ReportPeriod.DAY,
        rangeStart = LocalDate.parse(requireArguments().getString(ARG_RANGE_START).orEmpty()),
        rangeEndExclusive = LocalDate.parse(requireArguments().getString(ARG_RANGE_END).orEmpty()),
        totalDurationMs = requireArguments().getLong(ARG_TOTAL_DURATION_MS),
        values = requireArguments().getLongArray(ARG_VALUES)?.toList().orEmpty(),
    )

    companion object {
        private const val ARG_PERIOD = "period"
        private const val ARG_RANGE_START = "rangeStart"
        private const val ARG_RANGE_END = "rangeEnd"
        private const val ARG_TOTAL_DURATION_MS = "totalDurationMs"
        private const val ARG_VALUES = "values"
        private const val ARG_SHOW_CHART = "showChart"
        private const val ARG_SHOW_REFLECTION = "showReflection"
        private const val ARG_SHOW_MOTIVATION = "showMotivation"
        private const val ARG_REFLECTION_TEXT = "reflectionText"
        private const val STATE_SHOW_CHART = "stateShowChart"
        private const val STATE_SHOW_REFLECTION = "stateShowReflection"
        private const val STATE_SHOW_MOTIVATION = "stateShowMotivation"
        private const val STATE_REFLECTION_TEXT = "stateReflectionText"

        fun newInstance(
            data: FocusPosterData,
            sections: PosterSections,
            reflectionText: String,
        ): PosterCustomizeDialogFragment = PosterCustomizeDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_PERIOD, data.period.name)
                putString(ARG_RANGE_START, data.rangeStart.toString())
                putString(ARG_RANGE_END, data.rangeEndExclusive.toString())
                putLong(ARG_TOTAL_DURATION_MS, data.totalDurationMs)
                putLongArray(ARG_VALUES, data.values.toLongArray())
                putBoolean(ARG_SHOW_CHART, sections.showChart)
                putBoolean(ARG_SHOW_REFLECTION, sections.showReflection)
                putBoolean(ARG_SHOW_MOTIVATION, sections.showMotivation)
                putString(ARG_REFLECTION_TEXT, reflectionText)
            }
        }
    }
}
