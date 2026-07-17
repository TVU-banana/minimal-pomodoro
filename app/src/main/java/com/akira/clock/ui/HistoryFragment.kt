package com.akira.clock.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.akira.clock.ClockApplication
import com.akira.clock.R
import com.akira.clock.data.SessionEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/** Displays lifecycle-aware session history from the local Room source of truth. */
class HistoryFragment : Fragment(R.layout.fragment_history) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val list = view.findViewById<RecyclerView>(R.id.history_list)
        val empty = view.findViewById<TextView>(R.id.history_empty)
        val adapter = SessionAdapter()
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        val app = requireActivity().application as ClockApplication
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.repository.observeSessions().collect { sessions ->
                    adapter.submit(sessions)
                    empty.isVisible = sessions.isEmpty()
                }
            }
        }
    }
}

private class SessionAdapter : RecyclerView.Adapter<SessionViewHolder>() {
    private var items: List<SessionEntity> = emptyList()

    fun submit(newItems: List<SessionEntity>) {
        val oldItems = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldPosition: Int, newPosition: Int) =
                oldItems[oldPosition].id == newItems[newPosition].id
            override fun areContentsTheSame(oldPosition: Int, newPosition: Int) =
                oldItems[oldPosition] == newItems[newPosition]
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): SessionViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_text_card, parent, false)
        return SessionViewHolder(view as TextView)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) = holder.bind(items[position])
}

private class SessionViewHolder(private val text: TextView) : RecyclerView.ViewHolder(text) {
    fun bind(session: SessionEntity) {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val started = Instant.ofEpochMilli(session.startedAtUtcMs)
            .atZone(ZoneId.of(session.timezoneId))
            .format(formatter)
        text.text = text.context.getString(
            R.string.history_item,
            session.taskNameSnapshot.ifBlank { session.categoryNameSnapshot },
            session.categoryNameSnapshot,
            statusLabel(text.context, session.status),
            started,
            formatDuration(text.context, session.actualFocusMs),
            formatDuration(text.context, session.actualWarmupMs),
        )
        text.contentDescription = text.text
    }
}

internal fun formatDuration(context: android.content.Context, durationMs: Long): String {
    val totalMinutes = durationMs / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0) {
        context.getString(R.string.duration_hours_minutes, hours, minutes)
    } else {
        context.getString(R.string.duration_minutes, minutes)
    }
}

private fun statusLabel(context: android.content.Context, status: String): String =
    context.getString(
        when (status) {
            "COMPLETED" -> R.string.session_status_completed
            "ABORTED" -> R.string.session_status_aborted
            "PAUSED" -> R.string.session_status_paused
            else -> R.string.session_status_running
        },
    )
