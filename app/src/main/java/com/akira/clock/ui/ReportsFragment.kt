package com.akira.clock.ui

import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.akira.clock.ClockApplication
import com.akira.clock.R
import com.akira.clock.report.ReportPeriod
import com.akira.clock.report.ReportRow
import com.akira.clock.report.ReportService
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReportsFragment : Fragment(R.layout.fragment_reports) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val list = view.findViewById<RecyclerView>(R.id.report_list)
        val empty = view.findViewById<TextView>(R.id.report_empty)
        val periods = view.findViewById<RadioGroup>(R.id.report_period)
        val adapter = ReportAdapter()
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        val database = (requireActivity().application as ClockApplication).database
        val service = ReportService(database)

        fun load(period: ReportPeriod) {
            viewLifecycleOwner.lifecycleScope.launch {
                val rows = withContext(Dispatchers.IO) {
                    service.load(period, LocalDate.now(), ZoneId.systemDefault())
                }
                adapter.submit(rows)
                empty.isVisible = rows.isEmpty()
            }
        }
        periods.setOnCheckedChangeListener { _, checked ->
            load(
                when (checked) {
                    R.id.report_quarter -> ReportPeriod.QUARTER
                    R.id.report_year -> ReportPeriod.YEAR
                    else -> ReportPeriod.MONTH
                },
            )
        }
        load(ReportPeriod.MONTH)
    }
}

private class ReportAdapter : RecyclerView.Adapter<ReportViewHolder>() {
    private var items: List<ReportRow> = emptyList()
    fun submit(rows: List<ReportRow>) {
        val oldItems = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = rows.size
            override fun areItemsTheSame(oldPosition: Int, newPosition: Int) =
                oldItems[oldPosition].categoryId == rows[newPosition].categoryId
            override fun areContentsTheSame(oldPosition: Int, newPosition: Int) =
                oldItems[oldPosition] == rows[newPosition]
        })
        items = rows
        diff.dispatchUpdatesTo(this)
    }
    override fun getItemCount(): Int = items.size
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ReportViewHolder {
        val text = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_text_card, parent, false) as TextView
        return ReportViewHolder(text)
    }
    override fun onBindViewHolder(holder: ReportViewHolder, position: Int) = holder.bind(items[position])
}

private class ReportViewHolder(private val text: TextView) : RecyclerView.ViewHolder(text) {
    fun bind(row: ReportRow) {
        text.text = text.context.getString(
            R.string.report_row,
            row.categoryName,
            formatDuration(row.focusMs),
            formatDuration(row.warmupMs),
            row.completedCount,
            row.abortedCount,
        )
        text.contentDescription = text.text
    }
}
