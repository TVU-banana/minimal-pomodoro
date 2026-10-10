package com.akira.clock.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.akira.clock.R
import com.akira.clock.data.HabitEntity
import com.akira.clock.habit.HabitDayState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal data class HabitRow(
    val habit: HabitEntity,
    val date: LocalDate,
    val state: HabitDayState,
    val pending: Boolean,
    val completedCount: Int = 0,
    val targetCount: Int = 1,
)

/** Binds Room-derived state; checked semantics never act as a persistence cache. */
internal class HabitListAdapter(
    private val onCheck: (HabitRow) -> Unit,
    private val onEdit: (HabitEntity) -> Unit,
    private val onMore: (View, HabitEntity) -> Unit,
) : ListAdapter<HabitRow, HabitListAdapter.Holder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_habit, parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    internal inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val check: ImageButton = view.findViewById(R.id.habit_check)
        private val name: TextView = view.findViewById(R.id.habit_name)
        private val remaining: TextView = view.findViewById(R.id.habit_remaining)
        private val note: TextView = view.findViewById(R.id.habit_note)
        private val edit: View = view.findViewById(R.id.habit_edit)
        private val more: View = view.findViewById(R.id.habit_more)

        init {
            ViewCompat.setAccessibilityDelegate(check, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfoCompat,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.CheckBox"
                    info.isCheckable = true
                    info.isChecked = host.isSelected
                }
            })
        }

        fun bind(row: HabitRow) {
            val completed = row.state == HabitDayState.COMPLETED
            val context = itemView.context
            name.text = row.habit.name
            remaining.text = (row.targetCount - row.completedCount).coerceAtLeast(0).toString()
            remaining.visibility = if (!completed) {
                View.VISIBLE
            } else View.GONE
            note.text = row.habit.note
            note.visibility = if (row.habit.note.isEmpty()) View.GONE else View.VISIBLE
            val statusResource = when (row.state) {
                HabitDayState.COMPLETED -> R.string.habit_completed
                HabitDayState.INCOMPLETE -> R.string.habit_incomplete
                HabitDayState.NOT_APPLICABLE -> R.string.habit_not_applicable
                HabitDayState.FUTURE -> R.string.habit_future
            }
            check.setImageResource(
                if (completed) R.drawable.ic_habit_check else R.drawable.ic_habit_unchecked,
            )
            check.isSelected = completed
            check.isEnabled = !row.pending && row.state in EDITABLE_STATES
            check.contentDescription = context.getString(
                if (completed) R.string.habit_undo_complete else R.string.habit_mark_complete,
                row.habit.name,
                row.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
            )
            if (row.targetCount > 1) {
                check.contentDescription = context.getString(
                    if (completed) R.string.habit_undo_count_accessibility else {
                        R.string.habit_record_count_accessibility
                    },
                    row.habit.name,
                    row.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                )
            }
            ViewCompat.setStateDescription(check, if (row.state in EDITABLE_STATES) {
                context.getString(
                    R.string.habit_count_progress, row.completedCount, row.targetCount,
                )
            } else context.getString(statusResource))
            if (row.state !in EDITABLE_STATES) {
                check.contentDescription = context.getString(
                    R.string.habit_unavailable_accessibility, row.habit.name,
                    row.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                    context.getString(statusResource),
                )
            }
            check.setOnClickListener { onCheck(row) }
            edit.contentDescription = context.getString(
                R.string.habit_edit_accessibility, row.habit.name,
            )
            edit.setOnClickListener { onEdit(row.habit) }
            more.contentDescription = context.getString(
                R.string.habit_more_accessibility, row.habit.name,
            )
            more.setOnClickListener { onMore(more, row.habit) }
        }
    }

    private companion object {
        val EDITABLE_STATES = setOf(HabitDayState.COMPLETED, HabitDayState.INCOMPLETE)
        val DIFF = object : DiffUtil.ItemCallback<HabitRow>() {
            override fun areItemsTheSame(oldItem: HabitRow, newItem: HabitRow): Boolean =
                oldItem.habit.id == newItem.habit.id

            override fun areContentsTheSame(oldItem: HabitRow, newItem: HabitRow): Boolean =
                oldItem == newItem
        }
    }
}
