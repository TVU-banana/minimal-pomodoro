package com.akira.clock.ui

import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.akira.clock.ClockApplication
import com.akira.clock.R
import com.akira.clock.data.CategoryEntity
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shows one independently readable progress row for every active tag, limited to today. */
internal class TodayFragment : Fragment(R.layout.fragment_today) {
    private lateinit var rows: LinearLayout
    private var renderJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        rows = view.findViewById(R.id.today_tag_rows)
        view.findViewById<View>(R.id.today_back).setOnClickListener {
            (activity as? MainActivity)?.showTimer()
        }
        val app = requireActivity().application as ClockApplication
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                app.repository.observeTags().collect { tags -> render(app, tags) }
            }
        }
    }

    override fun onDestroyView() {
        renderJob?.cancel()
        renderJob = null
        super.onDestroyView()
    }

    private fun render(app: ClockApplication, tags: List<CategoryEntity>) {
        renderJob?.cancel()
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            if (tags.isEmpty()) {
                rows.removeAllViews()
                rows.addView(TextView(requireContext()).apply {
                    text = getString(R.string.today_empty)
                    setTextColor(ContextCompat.getColor(context, R.color.clock_on_surface_muted))
                })
                return@launch
            }
            val zone = ZoneId.systemDefault()
            val start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = start + 24 * 60 * 60_000L
            val progressMap = withContext(Dispatchers.IO) {
                tags.associate { tag ->
                    tag.id to app.database.sessionDao().dailyProgressForTagMs(tag.id, start, end)
                }
            }
            buildRows(tags, progressMap)
        }
    }

    private fun buildRows(tags: List<CategoryEntity>, progressMap: Map<String, Long>) {
        rows.removeAllViews()
        val density = resources.displayMetrics.density
        tags.forEach { tag ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_tag_row)
                val padH = (16 * density).toInt()
                val padV = (14 * density).toInt()
                setPadding(padH, padV, padH, padV)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = (8 * density).toInt() }
            }
            val name = TextView(requireContext()).apply {
                text = tag.name
                textSize = 16f
                setTextColor(ContextCompat.getColor(context, R.color.clock_on_surface))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            val completed = progressMap[tag.id] ?: 0L
            val goal = tag.dailyGoalMs
            val value = TextView(requireContext()).apply {
                text = getString(R.string.tag_progress, completed / 60_000L, goal / 60_000L)
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.clock_on_surface_muted))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (8 * density).toInt() }
            }
            val bar = ProgressBar(
                requireContext(),
                null,
                android.R.attr.progressBarStyleHorizontal,
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (6 * density).toInt(),
                ).apply { topMargin = (10 * density).toInt() }
                progressDrawable = ContextCompat.getDrawable(context, R.drawable.progress_bar_fill)
                (progressDrawable as? LayerDrawable)?.findDrawableByLayerId(android.R.id.progress)
                    ?.mutate()?.setTint(ContextCompat.getColor(context, R.color.clock_primary))
                max = 100
                progress = if (goal > 0) {
                    (completed * 100 / goal).toInt().coerceAtMost(100)
                } else {
                    0
                }
            }
            row.addView(name)
            row.addView(value)
            row.addView(bar)
            rows.addView(row)
        }
    }
}
