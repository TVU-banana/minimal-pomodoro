package com.akira.clock.ui

import android.app.DatePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.akira.clock.ClockApplication
import com.akira.clock.R
import com.akira.clock.data.HabitEntity
import com.akira.clock.habit.HabitCountChange
import com.akira.clock.habit.HabitDayState
import com.akira.clock.habit.HabitPolicy
import com.akira.clock.habit.HabitSnapshot
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * Independent daily habits destination. Calendar refreshes only read Room, including at midnight
 * and after timezone changes; old date keys are never rewritten to follow a new timezone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class HabitsFragment : Fragment(R.layout.fragment_habits) {
    private data class Dates(val selected: LocalDate, val end: LocalDate)

    private var today = LocalDate.now()
    private val dates = MutableStateFlow(Dates(today, today))
    private var snapshot = HabitSnapshot(emptyList(), emptyList())
    private val pending = mutableSetOf<Pair<String, LocalDate>>()
    private lateinit var adapter: HabitListAdapter
    private var renderedDates: Triple<Dates, LocalDate, Int>? = null
    private val repository get() = (requireActivity().application as ClockApplication).habits
    private val dateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshToday()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val configuration = resources.configuration
        // Short landscape windows prioritize full-sized check-in targets over the date strip.
        view.findViewById<View>(R.id.habit_date_scroll).isVisible = !(
            configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                configuration.screenHeightDp < 480 && configuration.fontScale > 1.3f
        )
        savedInstanceState?.getString(STATE_SELECTED)?.let { selected ->
            today = LocalDate.parse(savedInstanceState.getString(STATE_TODAY))
            dates.value = Dates(
                LocalDate.parse(selected),
                LocalDate.parse(savedInstanceState.getString(STATE_END)),
            )
        }
        adapter = HabitListAdapter(::check, ::edit, ::showMore)
        view.findViewById<RecyclerView>(R.id.habit_list).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@HabitsFragment.adapter
            itemAnimator = null
        }
        view.findViewById<View>(R.id.habit_add).setOnClickListener { create() }
        view.findViewById<View>(R.id.habit_previous).setOnClickListener { moveWeek(-1) }
        view.findViewById<View>(R.id.habit_next).setOnClickListener { moveWeek(1) }
        view.findViewById<View>(R.id.habit_today).setOnClickListener {
            refreshToday()
            dates.value = Dates(today, today)
        }
        view.findViewById<View>(R.id.habit_month).setOnClickListener { chooseDate() }
        view.findViewById<View>(R.id.habit_history).setOnClickListener { chooseDate() }
        view.doOnLayout { renderDates(view) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    dates.flatMapLatest { repository.observe(it.end.minusDays(6), it.end) }
                        .collect { value ->
                            snapshot = value
                            render(view)
                        }
                }
                launch {
                    while (true) {
                        refreshToday()
                        val next = today.plusDays(1).atStartOfDay(ZoneId.systemDefault())
                            .toInstant().toEpochMilli()
                        delay((next - System.currentTimeMillis()).coerceAtLeast(1_000L))
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshToday()
        ContextCompat.registerReceiver(
            requireContext(),
            dateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onPause() {
        requireContext().unregisterReceiver(dateReceiver)
        super.onPause()
    }

    override fun onDestroyView() {
        renderedDates = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SELECTED, dates.value.selected.toString())
        outState.putString(STATE_END, dates.value.end.toString())
        outState.putString(STATE_TODAY, today.toString())
    }

    private fun refreshToday() {
        val now = LocalDate.now()
        val current = dates.value
        if (now != today) {
            dates.value = Dates(
                if (current.selected == today || current.selected > now) now else current.selected,
                if (current.end == today || current.end > now) now else current.end,
            )
            today = now
        }
    }

    private fun render(view: View) {
        val selected = dates.value.selected
        renderDates(view)
        val rows = HabitPolicy.visibleHabits(snapshot, selected, today).map { habit ->
            HabitRow(
                habit, selected, HabitPolicy.stateFor(
                    habit, selected, today, snapshot.checkIns, snapshot.targets,
                ),
                (habit.id to selected) in pending,
                HabitPolicy.countFor(habit.id, selected, snapshot.checkIns),
                HabitPolicy.targetFor(habit.id, selected, snapshot.targets),
            )
        }
        adapter.submitList(rows)
        view.findViewById<TextView>(R.id.habit_progress).text = getString(
            R.string.habit_progress,
            rows.count { it.state == HabitDayState.COMPLETED },
            rows.count {
                it.state == HabitDayState.COMPLETED || it.state == HabitDayState.INCOMPLETE
            },
        )
        view.findViewById<View>(R.id.habit_empty).isVisible = rows.isEmpty()
        val firstHabit = snapshot.habits.isEmpty() && selected == today
        view.findViewById<TextView>(R.id.habit_empty_title).setText(
            if (firstHabit) R.string.habit_empty_title else R.string.habit_history_empty_title,
        )
        view.findViewById<TextView>(R.id.habit_empty_message).setText(
            if (firstHabit) R.string.habit_empty_message else R.string.habit_history_empty_message,
        )
    }

    private fun renderDates(view: View) {
        val current = dates.value
        val renderKey = Triple(current, today, view.width)
        if (renderedDates == renderKey) return
        if (renderedDates?.first?.selected != current.selected) {
            view.findViewById<View>(R.id.habit_feedback).apply {
                removeCallbacks(hideFeedback)
                isVisible = false
            }
        }
        renderedDates = renderKey
        val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        val month = view.findViewById<TextView>(R.id.habit_month)
        month.text = current.selected.format(formatter)
        month.contentDescription = getString(R.string.habit_choose_date, month.text)
        view.findViewById<View>(R.id.habit_next).apply {
            isEnabled = current.end < today
            alpha = if (isEnabled) 1f else 0.4f
        }
        val group = view.findViewById<LinearLayout>(R.id.habit_days)
        group.removeAllViews()
        val density = resources.displayMetrics.density
        val weekday = DateTimeFormatter.ofPattern("EE", Locale.getDefault())
        val labelPaint = TextView(requireContext()).apply { textSize = 14f }.paint
        // Scaled/localized weekday labels must stay on one line to keep date columns aligned.
        val widestLabel = (0..6).maxOf { index ->
            labelPaint.measureText(current.end.minusDays(index.toLong()).format(weekday))
        }
        val minimum = maxOf((48 * density).toInt(), (widestLabel + 16 * density).toInt() + 1)
        val available = view.width - view.paddingLeft - view.paddingRight
        val dayWidth = maxOf(minimum, available / 7)
        var selectedView: View? = null
        repeat(7) { index ->
            val day = current.end.minusDays((6 - index).toLong())
            val label = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    dayWidth, LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                minHeight = (64 * density).toInt()
                gravity = android.view.Gravity.CENTER
                setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
                textSize = 14f
                text = day.format(DateTimeFormatter.ofPattern("EE\nd", Locale.getDefault()))
                setTextColor(ContextCompat.getColor(context, if (day == current.selected) {
                    R.color.clock_primary
                } else {
                    R.color.clock_on_surface_muted
                }))
                isSelected = day == current.selected
                isFocusable = true
                contentDescription = getString(
                    R.string.habit_day_accessibility, day.format(formatter),
                    if (day == today) getString(R.string.habit_today_suffix) else "",
                )
                setBackgroundResource(R.drawable.bg_habit_day)
                setOnClickListener {
                    dates.value = current.copy(selected = day)
                    render(view)
                }
            }
            group.addView(label)
            if (label.isSelected) selectedView = label
        }
        group.post {
            selectedView?.let { child ->
                view.findViewById<HorizontalScrollView>(R.id.habit_date_scroll)
                    .scrollTo((child.left - available / 2 + dayWidth / 2).coerceAtLeast(0), 0)
            }
        }
    }

    private fun moveWeek(direction: Int) {
        val end = dates.value.end.plusDays(direction * 7L).coerceAtMost(today)
        dates.value = Dates(end, end)
    }

    private fun chooseDate() {
        val day = dates.value.selected
        DatePickerDialog(requireContext(), { _, year, month, date ->
            val selected = LocalDate.of(year, month + 1, date)
            dates.value = Dates(selected, selected)
        }, day.year, day.monthValue - 1, day.dayOfMonth).apply {
            datePicker.maxDate = System.currentTimeMillis()
            show()
        }
    }

    private fun create() {
        if (childFragmentManager.findFragmentByTag(EDITOR_TAG) == null) {
            HabitEditorDialogFragment().show(childFragmentManager, EDITOR_TAG)
        }
    }

    private fun edit(habit: HabitEntity) {
        if (childFragmentManager.findFragmentByTag(EDITOR_TAG) == null) {
            HabitEditorDialogFragment.editing(
                habit, HabitPolicy.targetFor(habit.id, today, snapshot.targets),
            ).show(childFragmentManager, EDITOR_TAG)
        }
    }

    private fun check(row: HabitRow) {
        val key = row.habit.id to row.date
        if (!pending.add(key)) return
        render(requireView())
        mutate(onFinish = {
            pending.remove(key)
            view?.let(::render)
        }) {
            val result = repository.record(row.habit.id, row.date)
            view?.let { currentView ->
                if (dates.value.selected == row.date) showFeedback(currentView, row, result)
            }
        }
    }

    private fun undo(id: String, date: LocalDate, revision: Long? = null) {
        val key = id to date
        if (!pending.add(key)) return
        view?.let(::render)
        mutate(onFinish = {
            pending.remove(key)
            view?.let(::render)
        }) { repository.undoOnce(id, date, revision) }
    }

    private fun showFeedback(view: View, row: HabitRow, result: HabitCountChange) {
        val feedback = view.findViewById<View>(R.id.habit_feedback)
        feedback.removeCallbacks(hideFeedback)
        view.findViewById<TextView>(R.id.habit_feedback_text).text = getString(
            R.string.habit_count_progress, result.checkIn.completedCount, result.targetCount,
        )
        view.findViewById<View>(R.id.habit_feedback_undo).apply {
            isVisible = result.checkIn.completedCount > result.previousCount
            setOnClickListener {
                feedback.isVisible = false
                undo(row.habit.id, row.date, result.checkIn.updatedAtUtcMs)
            }
        }
        feedback.isVisible = true
        val manager = requireContext().getSystemService(Context.ACCESSIBILITY_SERVICE)
            as android.view.accessibility.AccessibilityManager
        val duration = if (android.os.Build.VERSION.SDK_INT >= 29) {
            manager.getRecommendedTimeoutMillis(5_000,
                android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT or
                    android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS)
        } else 5_000
        feedback.postDelayed(hideFeedback, duration.toLong())
    }

    private val hideFeedback = Runnable {
        view?.findViewById<View>(R.id.habit_feedback)?.isVisible = false
    }

    private fun showMore(anchor: View, habit: HabitEntity) {
        val active = snapshot.habits.filter { it.archivedLocalDateIso == null }
        val index = active.indexOfFirst { it.id == habit.id }
        PopupMenu(
            requireContext(),
            anchor,
            android.view.Gravity.END,
            androidx.appcompat.R.attr.popupMenuStyle,
            R.style.Clock_HabitPopupTheme,
        ).apply {
            menu.add(0, EDIT, 0, R.string.habit_edit_title)
            val selected = dates.value.selected
            val count = HabitPolicy.countFor(habit.id, selected, snapshot.checkIns)
            menu.add(0, UNDO, 1, R.string.habit_undo_once).isEnabled =
                count > 0 && (habit.id to selected) !in pending &&
                    (habit.archivedLocalDateIso == null || selected < today)
            if (index >= 0) {
                menu.add(0, UP, 1, R.string.habit_move_up).isEnabled = index > 0
                menu.add(0, DOWN, 2, R.string.habit_move_down).isEnabled = index < active.lastIndex
                menu.add(0, ARCHIVE, 3, R.string.habit_archive)
            }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    EDIT -> edit(habit)
                    UNDO -> undo(habit.id, selected)
                    UP -> mutate { repository.move(habit.id, -1) }
                    DOWN -> mutate { repository.move(habit.id, 1) }
                    ARCHIVE -> confirmArchive(habit)
                }
                true
            }
            show()
        }
    }

    private fun confirmArchive(habit: HabitEntity) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.habit_archive_title, habit.name))
            .setMessage(R.string.habit_archive_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.habit_archive) { _, _ ->
                mutate { repository.archive(habit.id) }
            }
            .create().apply {
                setOnShowListener { applyClockDialogTheme() }
                show()
            }
    }

    private fun mutate(onFinish: () -> Unit = {}, action: suspend () -> Unit) {
        val app = requireActivity().application as ClockApplication
        app.habitCommandScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(app, R.string.habit_operation_failed, Toast.LENGTH_LONG).show()
            } finally {
                onFinish()
            }
        }
    }

    private companion object {
        const val STATE_SELECTED = "habitSelectedDate"
        const val STATE_END = "habitRangeEnd"
        const val STATE_TODAY = "habitToday"
        const val EDITOR_TAG = "habitEditor"
        const val EDIT = 1
        const val UP = 2
        const val DOWN = 3
        const val ARCHIVE = 4
        const val UNDO = 5
    }
}
