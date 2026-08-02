package com.akira.clock.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.akira.clock.ClockApplication
import com.akira.clock.R
import com.akira.clock.data.SessionEntity
import com.akira.clock.report.DEFAULT_TAG_COLOR
import com.akira.clock.report.ReportData
import com.akira.clock.report.ReportPeriod
import com.akira.clock.report.ReportRow
import com.akira.clock.report.ReportService
import com.akira.clock.report.FocusPosterData
import com.akira.clock.report.FocusPosterDataFactory
import com.akira.clock.export.FocusPosterRenderer
import com.akira.clock.ui.render.BarChartView
import com.akira.clock.ui.render.HeatmapView
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReportsFragment : Fragment(R.layout.fragment_reports) {
    private var pendingPoster: FocusPosterData? = null
    private val createPoster = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        val poster = pendingPoster ?: return@registerForActivityResult
        uri?.let { destination ->
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching {
                    val bitmap = withContext(Dispatchers.Default) {
                        FocusPosterRenderer(requireContext().applicationContext).render(poster)
                    }
                    withContext(Dispatchers.IO) {
                        requireNotNull(requireContext().contentResolver.openOutputStream(destination, "w"))
                            .use { stream ->
                                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream))
                            }
                    }
                    bitmap.recycle()
                }.onSuccess {
                    Toast.makeText(requireContext(), R.string.poster_export_success, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.data_operation_failed, it.message ?: it.javaClass.simpleName),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val list = view.findViewById<RecyclerView>(R.id.report_list)
        val empty = view.findViewById<TextView>(R.id.report_empty)
        val summary = view.findViewById<TextView>(R.id.report_summary)
        val exportPoster = view.findViewById<Button>(R.id.report_export_poster)
        val periods = view.findViewById<RadioGroup>(R.id.report_period)
        val adapter = ReportAdapter()

        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        val app = requireActivity().application as ClockApplication
        val service = ReportService(app.database)
        val dao = app.database.sessionDao()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()

        fun load(period: ReportPeriod) {
            viewLifecycleOwner.lifecycleScope.launch {
                val data = withContext(Dispatchers.IO) {
                    service.load(period, today, zone)
                }
                val hasContent = data.rows.isNotEmpty() || data.sessions.isNotEmpty()
                empty.isVisible = !hasContent
                summary.isVisible = hasContent
                exportPoster.isVisible = hasContent
                if (hasContent) {
                    summary.text = getString(
                        R.string.report_summary,
                        formatDuration(requireContext(), data.rows.sumOf { it.investedMs }),
                    )
                }

                pendingPoster = withContext(Dispatchers.IO) {
                    FocusPosterDataFactory.create(period, dao.allForExport(), today, zone)
                }

                val items = mutableListOf<ReportItem>()

                // Chart header
                when (period) {
                    ReportPeriod.DAY -> {
                        val barData = withContext(Dispatchers.IO) { buildDayBarData(dao, today, zone) }
                        items += ReportItem.BarChart(getString(R.string.report_barchart_title), barData)
                    }
                    ReportPeriod.WEEK -> {
                        val barData = withContext(Dispatchers.IO) { buildWeekBarData(dao, today, zone) }
                        items += ReportItem.BarChart(getString(R.string.report_barchart_title), barData)
                    }
                    ReportPeriod.MONTH -> {
                        val heatData = withContext(Dispatchers.IO) { buildMonthHeatmap(dao, today, zone) }
                        items += ReportItem.Heatmap(getString(R.string.report_heatmap_title), heatData)
                    }
                    ReportPeriod.YEAR -> {
                        val heatData = withContext(Dispatchers.IO) { buildYearHeatmap(dao, today, zone) }
                        items += ReportItem.Heatmap(getString(R.string.report_heatmap_title), heatData)
                    }
                    ReportPeriod.ALL_TIME -> {
                        val heatData = withContext(Dispatchers.IO) { buildAllTimeHeatmap(dao, today, zone) }
                        items += ReportItem.Heatmap(getString(R.string.report_heatmap_title), heatData)
                    }
                }

                // Detail items
                items += buildDetailItems(data)
                adapter.submit(items)
            }
        }

        exportPoster.setOnClickListener {
            val poster = pendingPoster ?: return@setOnClickListener
            createPoster.launch("clock-${poster.rangeStart}-${periodFileLabel(poster.period)}.png")
        }

        periods.setOnCheckedChangeListener { _, checked ->
            load(
                when (checked) {
                    R.id.report_all -> ReportPeriod.ALL_TIME
                    R.id.report_year -> ReportPeriod.YEAR
                    R.id.report_week -> ReportPeriod.WEEK
                    R.id.report_day -> ReportPeriod.DAY
                    else -> ReportPeriod.MONTH
                },
            )
        }

        load(ReportPeriod.MONTH)
    }

    private fun periodFileLabel(period: ReportPeriod): String = when (period) {
        ReportPeriod.DAY -> "day"
        ReportPeriod.WEEK -> "week"
        ReportPeriod.MONTH -> "month"
        ReportPeriod.YEAR -> "year"
        ReportPeriod.ALL_TIME -> "all-time"
    }

    private suspend fun buildDayBarData(
        dao: com.akira.clock.data.SessionDao,
        today: LocalDate,
        zone: ZoneId,
    ): List<BarChartView.TimeBarItem> {
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = dayStart + 24 * 60 * 60_000L
        val rows = dao.focusByHour(dayStart, dayEnd)
        val hourMap = rows.associate { it.hourIndex to it.totalMs }
        return (0..23).map { hour ->
            BarChartView.TimeBarItem(label = "${hour}", valueMs = hourMap[hour] ?: 0L)
        }
    }

    private suspend fun buildWeekBarData(
        dao: com.akira.clock.data.SessionDao,
        today: LocalDate,
        zone: ZoneId,
    ): List<BarChartView.TimeBarItem> {
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekStartMs = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val weekEndMs = weekStart.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = dao.focusByDayIndex(weekStartMs, weekEndMs)
        val dayMap = rows.associate { it.dayIndex to it.totalMs }
        val dayNames = arrayOf("一", "二", "三", "四", "五", "六", "日")
        return dayNames.mapIndexed { index, name ->
            BarChartView.TimeBarItem(label = name, valueMs = dayMap[index] ?: 0L)
        }
    }

    private suspend fun buildMonthHeatmap(
        dao: com.akira.clock.data.SessionDao,
        today: LocalDate,
        zone: ZoneId,
    ): HeatmapData {
        val monthStart = today.withDayOfMonth(1)
        val endExclusive = today.plusDays(1)
        val startMs = monthStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        val days = (endExclusive.toEpochDay() - monthStart.toEpochDay()).toInt()
        val rows = dao.focusByDayIndex(startMs, endMs)
        val data = rows.associate { it.dayIndex to (it.totalMs / 60_000).toInt() }
        return HeatmapData(data, monthStart, days)
    }

    private suspend fun buildYearHeatmap(
        dao: com.akira.clock.data.SessionDao,
        today: LocalDate,
        zone: ZoneId,
    ): HeatmapData {
        val yearStart = today.withDayOfYear(1)
        val endExclusive = today.plusDays(1)
        val startMs = yearStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        val days = (endExclusive.toEpochDay() - yearStart.toEpochDay()).toInt()
        val rows = dao.focusByDayIndex(startMs, endMs)
        val data = rows.associate { it.dayIndex to (it.totalMs / 60_000).toInt() }
        return HeatmapData(data, yearStart, days)
    }

    private suspend fun buildAllTimeHeatmap(
        dao: com.akira.clock.data.SessionDao,
        today: LocalDate,
        zone: ZoneId,
    ): HeatmapData {
        val allSessions = dao.allForExport()
        if (allSessions.isEmpty()) return HeatmapData(emptyMap(), today, 1)
        val earliest = java.time.Instant.ofEpochMilli(allSessions.minOf { it.startedAtUtcMs })
            .atZone(zone)
            .toLocalDate()
        val startMs = earliest.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val days = (today.toEpochDay() - earliest.toEpochDay() + 1).toInt()
        val rows = dao.focusByDayIndex(startMs, endMs)
        val data = rows.associate { it.dayIndex to (it.totalMs / 60_000).toInt() }
        return HeatmapData(data, earliest, days)
    }

    private fun buildDetailItems(data: ReportData): List<ReportItem> {
        val items = mutableListOf<ReportItem>()
        if (data.rows.isNotEmpty()) {
            items += ReportItem.Section(getString(R.string.report_section_tags))
            data.rows.forEach { items += ReportItem.TagRow(it) }
        }
        if (data.sessions.isNotEmpty()) {
            items += ReportItem.Section(
                getString(R.string.report_section_sessions) + " · " + data.sessions.size,
            )
            data.sessions.forEach { session ->
                val color = data.categoryColors[session.categoryId] ?: DEFAULT_TAG_COLOR
                items += ReportItem.SessionRow(session, color)
            }
        }
        return items
    }
}

private sealed class ReportItem {
    data class Heatmap(val title: String, val data: HeatmapData) : ReportItem()
    data class BarChart(val title: String, val bars: List<com.akira.clock.ui.render.BarChartView.TimeBarItem>) : ReportItem()
    data class Section(val title: String) : ReportItem()
    data class TagRow(val row: ReportRow) : ReportItem()
    data class SessionRow(val session: SessionEntity, val colorArgb: Long) : ReportItem()
}

private class ReportAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var items: List<ReportItem> = emptyList()

    fun submit(newItems: List<ReportItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is ReportItem.Heatmap -> TYPE_HEATMAP
        is ReportItem.BarChart -> TYPE_BARCHART
        is ReportItem.Section -> TYPE_SECTION
        is ReportItem.TagRow -> TYPE_TAG
        is ReportItem.SessionRow -> TYPE_SESSION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEATMAP -> HeatmapViewHolder(inflater.inflate(R.layout.item_chart_heatmap, parent, false))
            TYPE_BARCHART -> BarChartViewHolder(inflater.inflate(R.layout.item_chart_barchart, parent, false))
            TYPE_SECTION -> SectionViewHolder(inflater.inflate(R.layout.item_report_section, parent, false))
            TYPE_TAG -> TagViewHolder(inflater.inflate(R.layout.item_report, parent, false))
            else -> SessionViewHolder(inflater.inflate(R.layout.item_report_session, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ReportItem.Heatmap -> (holder as HeatmapViewHolder).bind(item)
            is ReportItem.BarChart -> (holder as BarChartViewHolder).bind(item)
            is ReportItem.Section -> (holder as SectionViewHolder).bind(item)
            is ReportItem.TagRow -> (holder as TagViewHolder).bind(item.row)
            is ReportItem.SessionRow -> (holder as SessionViewHolder).bind(item.session, item.colorArgb)
        }
    }

    private companion object {
        const val TYPE_HEATMAP = 0
        const val TYPE_BARCHART = 1
        const val TYPE_SECTION = 2
        const val TYPE_TAG = 3
        const val TYPE_SESSION = 4
    }
}

private class HeatmapViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val title: TextView = view.findViewById(R.id.chart_title)
    private val heatmap: HeatmapView = view.findViewById(R.id.chart_heatmap)

    fun bind(item: ReportItem.Heatmap) {
        title.text = item.title
        heatmap.setData(item.data.activityData, item.data.rangeStart, item.data.days)
    }
}

private data class HeatmapData(
    val activityData: Map<Int, Int>,
    val rangeStart: LocalDate,
    val days: Int,
)

private class BarChartViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val title: TextView = view.findViewById(R.id.chart_title)
    private val barchart: BarChartView = view.findViewById(R.id.chart_barchart)

    fun bind(item: ReportItem.BarChart) {
        title.text = item.title
        barchart.setTimeData(item.bars)
    }
}

private class SectionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val text: TextView = view as TextView
    fun bind(item: ReportItem.Section) {
        text.text = item.title
    }
}

private class TagViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val dot: View = view.findViewById(R.id.report_dot)
    private val text: TextView = view.findViewById(R.id.report_text)

    fun bind(row: ReportRow) {
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(row.colorArgb.toInt())
        }
        text.text = text.context.getString(
            R.string.report_row,
            row.categoryName,
            formatDuration(text.context, row.focusMs),
            formatDuration(text.context, row.warmupMs),
            row.completedCount,
            row.abortedCount,
        )
        text.contentDescription = text.text
    }
}

private class SessionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val dot: View = view.findViewById(R.id.session_dot)
    private val name: TextView = view.findViewById(R.id.session_name)
    private val status: TextView = view.findViewById(R.id.session_status)
    private val detail: TextView = view.findViewById(R.id.session_detail)
    private val reason: TextView = view.findViewById(R.id.session_reason)

    fun bind(session: SessionEntity, colorArgb: Long) {
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorArgb.toInt())
        }
        name.text = session.taskNameSnapshot.ifBlank { session.categoryNameSnapshot }
        status.text = statusLabel(status.context, session.status)
        detail.text = detail.context.getString(
            R.string.report_session_detail,
            formatSessionDate(session),
            formatDuration(detail.context, session.actualFocusMs),
            formatDuration(detail.context, session.actualWarmupMs),
        )
        val abortReason = session.abortReason
        if (abortReason.isNullOrBlank()) {
            reason.isVisible = false
        } else {
            reason.isVisible = true
            reason.text = reason.context.getString(R.string.report_session_reason, abortReason)
        }
        itemView.contentDescription = name.text.toString() + " " + detail.text
    }
}
