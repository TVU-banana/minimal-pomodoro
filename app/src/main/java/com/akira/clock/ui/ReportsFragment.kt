package com.akira.clock.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.akira.clock.ClockApplication
import com.akira.clock.R
import com.akira.clock.data.SessionEntity
import com.akira.clock.export.FocusPosterRenderer
import com.akira.clock.report.DEFAULT_TAG_COLOR
import com.akira.clock.report.FocusPosterData
import com.akira.clock.report.FocusPosterDataFactory
import com.akira.clock.report.FocusPosterDraft
import com.akira.clock.report.HeatmapPage
import com.akira.clock.report.ReportData
import com.akira.clock.report.ReportNavigation
import com.akira.clock.report.ReportPeriod
import com.akira.clock.report.ReportRow
import com.akira.clock.report.ReportSelection
import com.akira.clock.report.ReportService
import com.akira.clock.ui.render.HeatmapView
import com.akira.clock.ui.render.LineChartView
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Renders period-based focus reports (Day, Week, Year) with heatmaps, charts, and session history.
 *
 * Data aggregation runs asynchronously on Dispatchers.IO via ReportService. Active loading
 * jobs are canceled when hidden or navigated away, and refreshed upon return to RESUMED.
 */
internal class ReportsFragment : Fragment(R.layout.fragment_reports),
    PosterCustomizeDialogFragment.Listener {
    private var pendingPoster: FocusPosterData? = null
    private var pendingDraft: FocusPosterDraft? = null
    private var galleryPoster: FocusPosterDraft? = null
    private var reflectionDate: LocalDate? = null
    private var reportSelection: ReportSelection? = null
    private var heatmapPageIndex = 0
    private var loadJob: Job? = null
    private var refreshVisibleReport: (() -> Unit)? = null
    private val galleryPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val poster = galleryPoster
        galleryPoster = null
        if (granted && poster != null) {
            savePosterToGallery(poster)
        } else if (!granted) {
            Toast.makeText(
                requireContext(),
                R.string.poster_gallery_permission_required,
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    private val createPoster = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        val poster = pendingDraft ?: return@registerForActivityResult
        uri?.let { destination ->
            val resolver = requireContext().contentResolver
            savePoster(poster) { bitmap ->
                requireNotNull(resolver.openOutputStream(destination, "w")).use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        // Large type must not leave the chart with a tiny scroll target below fixed controls.
        val layout = if (resources.configuration.fontScale >= LARGE_TEXT_SCALE) {
            R.layout.fragment_reports_large_text
        } else {
            R.layout.fragment_reports
        }
        return inflater.inflate(layout, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val list = view.findViewById<RecyclerView>(R.id.report_list)
        list.layoutManager = LinearLayoutManager(requireContext())
        val controls = if (view.findViewById<RadioGroup>(R.id.report_period) == null) {
            layoutInflater.inflate(R.layout.report_controls, list, false)
        } else {
            view
        }
        val empty = controls.findViewById<TextView>(R.id.report_empty)
        val summary = controls.findViewById<TextView>(R.id.report_summary)
        val exportPoster = controls.findViewById<Button>(R.id.report_export_poster)
        val periods = controls.findViewById<RadioGroup>(R.id.report_period)
        val rangePrevious = controls.findViewById<ImageButton>(R.id.report_range_previous)
        val rangeTitle = controls.findViewById<TextView>(R.id.report_range_title)
        val rangeNext = controls.findViewById<ImageButton>(R.id.report_range_next)
        lateinit var refreshReport: () -> Unit
        val adapter = ReportAdapter(
            controlsHeader = controls.takeIf { it !== view },
            onPreviousHeatmapPage = {
                if (heatmapPageIndex > 0) {
                    heatmapPageIndex--
                    refreshReport()
                }
            },
            onNextHeatmapPage = {
                val selection = reportSelection
                if (selection != null) {
                    val page = ReportNavigation.heatmapPage(selection, heatmapPageIndex)
                    if (ReportNavigation.nextHeatmapPageOrNull(page, LocalDate.now()) != null) {
                        heatmapPageIndex++
                        refreshReport()
                    }
                }
            },
        )

        if (controls !== view) list.itemAnimator = null
        list.adapter = adapter
        val app = requireActivity().application as ClockApplication
        val service = ReportService(app.database)
        val dao = app.database.sessionDao()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val restoredPeriod = savedInstanceState?.getString(STATE_REPORT_PERIOD)
            ?.let { ReportPeriod.entries.firstOrNull { period -> period.name == it } }
            ?: reportSelection?.period ?: ReportPeriod.DAY
        val restoredAnchor = savedInstanceState?.getString(STATE_REPORT_ANCHOR)
            ?.runCatching(LocalDate::parse)
            ?.getOrNull()
            ?: reportSelection?.anchor ?: today
        reportSelection = ReportNavigation.normalize(
            ReportSelection(restoredPeriod, restoredAnchor),
            today,
        )
        heatmapPageIndex = savedInstanceState?.getInt(STATE_HEATMAP_PAGE_INDEX)
            ?.takeIf { it in 0..3 }
            ?: if (savedInstanceState == null) heatmapPageIndex else {
                ReportNavigation.pageIndexFor(reportSelection!!.anchor)
            }

        fun renderNavigation(selection: ReportSelection) {
            val currentDate = LocalDate.now()
            rangeTitle.text = formatRangeTitle(selection, currentDate)
            rangeNext.isEnabled = ReportNavigation.nextOrNull(selection, currentDate) != null
            rangeNext.alpha = if (rangeNext.isEnabled) 1f else DISABLED_ARROW_ALPHA
        }

        fun load(selection: ReportSelection) {
            loadJob?.cancel()
            val currentDate = LocalDate.now()
            val snapshot = selection
            val pageIndex = heatmapPageIndex
            reflectionDate = snapshot.anchor
            renderNavigation(snapshot)
            exportPoster.isEnabled = false
            loadJob = viewLifecycleOwner.lifecycleScope.launch {
                val data = withContext(Dispatchers.IO) {
                    service.load(snapshot.period, snapshot.anchor, zone)
                }
                if (reportSelection != snapshot || pageIndex != heatmapPageIndex) return@launch
                val hasContent = data.rows.isNotEmpty() || data.sessions.isNotEmpty()
                empty.isVisible = !hasContent
                summary.isVisible = hasContent
                exportPoster.isVisible = true
                exportPoster.isEnabled = true
                if (hasContent) {
                    summary.text = getString(
                        R.string.report_summary,
                        formatDuration(requireContext(), data.rows.sumOf { it.focusMs }),
                    )
                }

                pendingPoster = withContext(Dispatchers.IO) {
                    FocusPosterDataFactory.create(
                        snapshot.period, dao.allForExport(), snapshot.anchor, zone,
                        app.database.sessionSegmentDao().allForExport(),
                    )
                }

                val items = mutableListOf<ReportItem>()

                // Chart header
                when (snapshot.period) {
                    ReportPeriod.DAY -> {
                        val lineData = withContext(Dispatchers.IO) {
                            requireNotNull(pendingPoster).values.mapIndexed { hour, value ->
                                LineChartView.TimePoint(hour.toString(), value)
                            }
                        }
                        items += ReportItem.LineChart(
                            getString(R.string.report_distribution_title), lineData,
                        )
                    }
                    ReportPeriod.WEEK -> {
                        val lineData = withContext(Dispatchers.IO) {
                            requireNotNull(pendingPoster).values.mapIndexed { day, value ->
                                LineChartView.TimePoint(
                                    getString(R.string.chart_weekday_labels).split(",")[day], value,
                                )
                            }
                        }
                        items += ReportItem.LineChart(
                            getString(R.string.report_distribution_title), lineData,
                        )
                    }
                    ReportPeriod.YEAR -> {
                        val page = ReportNavigation.heatmapPage(snapshot, pageIndex)
                        val heatData = withContext(Dispatchers.IO) {
                            buildHeatmapPage(dao, page, currentDate, zone)
                        }
                        items += ReportItem.Heatmap(
                            getString(R.string.report_heatmap_title),
                            heatData,
                        )
                    }
                }

                // Detail items
                items += buildDetailItems(data)
                adapter.submit(items)
            }
        }

        refreshReport = {
            val selection = reportSelection
                ?: ReportNavigation.currentSelection(ReportPeriod.DAY, LocalDate.now())
            load(selection)
        }

        exportPoster.setOnClickListener {
            val poster = pendingPoster ?: return@setOnClickListener
            showPosterCustomize(poster)
        }

        periods.check(
            when (reportSelection!!.period) {
                ReportPeriod.YEAR -> R.id.report_year
                ReportPeriod.WEEK -> R.id.report_week
                ReportPeriod.DAY -> R.id.report_day
            },
        )
        periods.setOnCheckedChangeListener { _, checked ->
            val period = when (checked) {
                R.id.report_year -> ReportPeriod.YEAR
                R.id.report_week -> ReportPeriod.WEEK
                else -> ReportPeriod.DAY
            }
            reportSelection = ReportNavigation.currentSelection(period, LocalDate.now())
            heatmapPageIndex = ReportNavigation.pageIndexFor(reportSelection!!.anchor)
            refreshReport()
        }

        rangePrevious.setOnClickListener {
            reportSelection = ReportNavigation.previous(requireNotNull(reportSelection))
            refreshReport()
        }
        rangeNext.setOnClickListener {
            reportSelection = ReportNavigation.nextOrNull(
                requireNotNull(reportSelection),
                LocalDate.now(),
            ) ?: return@setOnClickListener
            refreshReport()
        }
        refreshVisibleReport = refreshReport
    }

    override fun onResume() {
        super.onResume()
        refreshVisibleReport?.invoke()
    }

    override fun onPause() {
        loadJob?.cancel()
        super.onPause()
    }

    override fun onDestroyView() {
        refreshVisibleReport = null
        loadJob = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        reportSelection?.let { selection ->
            outState.putString(STATE_REPORT_PERIOD, selection.period.name)
            outState.putString(STATE_REPORT_ANCHOR, selection.anchor.toString())
        }
        outState.putInt(STATE_HEATMAP_PAGE_INDEX, heatmapPageIndex)
    }

    override fun onPosterDraftReady(draft: FocusPosterDraft, saveAs: Boolean) {
        val app = requireActivity().application as ClockApplication
        val date = reflectionDate ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    app.repository.saveDailyReflection(
                        date, draft.reflectionText, System.currentTimeMillis(),
                    )
                }
            }.onSuccess {
                app.settings.posterSections = draft.sections
                pendingDraft = draft
                if (saveAs) createPoster.launch(posterFileName(draft))
                else savePosterAfterPermissionCheck(draft)
            }.onFailure(::showOperationError)
        }
    }

    private fun showPosterCustomize(poster: FocusPosterData) {
        val app = requireActivity().application as ClockApplication
        val date = reflectionDate ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { app.repository.dailyReflection(date) }
            }.onSuccess { reflection ->
                if (childFragmentManager.findFragmentByTag(POSTER_CUSTOMIZE_DIALOG_TAG) == null) {
                    PosterCustomizeDialogFragment.newInstance(
                        poster, app.settings.posterSections, reflection,
                    ).show(childFragmentManager, POSTER_CUSTOMIZE_DIALOG_TAG)
                }
            }.onFailure(::showOperationError)
        }
    }

    private fun savePosterToGallery(poster: FocusPosterDraft) {
        val resolver = requireContext().contentResolver
        savePoster(poster) { bitmap ->
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, posterFileName(poster))
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/Clock",
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val destination = requireNotNull(
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values),
            )
            try {
                requireNotNull(resolver.openOutputStream(destination, "w")).use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(destination, values, null, null)
                }
            } catch (error: Throwable) {
                resolver.delete(destination, null, null)
                throw error
            }
        }
    }

    private fun savePosterAfterPermissionCheck(poster: FocusPosterDraft) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            savePosterToGallery(poster)
        } else {
            galleryPoster = poster
            galleryPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun savePoster(poster: FocusPosterDraft, write: suspend (Bitmap) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val bitmap = withContext(Dispatchers.Default) {
                    FocusPosterRenderer(requireContext().applicationContext).render(poster)
                }
                try {
                    withContext(Dispatchers.IO) { write(bitmap) }
                } finally {
                    bitmap.recycle()
                }
            }.onSuccess {
                Toast.makeText(
                    requireContext(), R.string.poster_export_success, Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { error -> showOperationError(error) }
        }
    }

    private fun showOperationError(error: Throwable) {
        Toast.makeText(
            requireContext(),
            getString(R.string.data_operation_failed, error.message ?: error.javaClass.simpleName),
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun posterFileName(poster: FocusPosterDraft): String =
        "clock-${poster.data.rangeStart}-${periodFileLabel(poster.data.period)}.png"

    private fun periodFileLabel(period: ReportPeriod): String = when (period) {
        ReportPeriod.DAY -> "day"
        ReportPeriod.WEEK -> "week"
        ReportPeriod.YEAR -> "year"
    }

    private companion object {
        const val LARGE_TEXT_SCALE = 1.5f
        const val POSTER_CUSTOMIZE_DIALOG_TAG = "posterCustomize"
        const val STATE_REPORT_PERIOD = "reportPeriod"
        const val STATE_REPORT_ANCHOR = "reportAnchor"
        const val STATE_HEATMAP_PAGE_INDEX = "heatmapPageIndex"
        const val DISABLED_ARROW_ALPHA = 0.28f
    }

    private suspend fun buildHeatmapPage(
        dao: com.akira.clock.data.SessionDao,
        page: HeatmapPage,
        today: LocalDate,
        zone: ZoneId,
    ): HeatmapData {
        val startMs = page.start.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = page.endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        val days = (page.endExclusive.toEpochDay() - page.start.toEpochDay()).toInt()
        val rows = dao.focusByDayIndex(startMs, endMs)
        val data = rows.associate { it.dayIndex to (it.totalMs / 60_000).toInt() }
        return HeatmapData(
            activityData = data,
            rangeStart = page.start,
            days = days,
            visibleThrough = minOf(today, page.endExclusive.minusDays(1)),
            pageTitle = getString(
                R.string.heatmap_page_title,
                page.year,
                page.start.monthValue,
                page.endExclusive.minusDays(1).monthValue,
            ),
            previousEnabled = ReportNavigation.previousHeatmapPageOrNull(page) != null,
            nextEnabled = ReportNavigation.nextHeatmapPageOrNull(page, today) != null,
        )
    }

    private fun formatRangeTitle(
        selection: ReportSelection,
        today: LocalDate,
    ): String = when (selection.period) {
        ReportPeriod.YEAR -> getString(R.string.report_range_year, selection.anchor.year)
        ReportPeriod.WEEK -> {
            val start = ReportNavigation.weekStart(selection.anchor)
            val end = start.plusDays(6)
            if (start.year == end.year) {
                getString(
                    R.string.report_range_week_same_year,
                    start.monthValue,
                    start.dayOfMonth,
                    end.monthValue,
                    end.dayOfMonth,
                )
            } else {
                getString(
                    R.string.report_range_week_cross_year,
                    start.year,
                    start.monthValue,
                    start.dayOfMonth,
                    end.year,
                    end.monthValue,
                    end.dayOfMonth,
                )
            }
        }
        ReportPeriod.DAY -> when {
            selection.anchor == today -> getString(
                R.string.report_range_today,
                today.monthValue,
                today.dayOfMonth,
            )
            selection.anchor == today.minusDays(1) -> getString(
                R.string.report_range_yesterday,
                selection.anchor.monthValue,
                selection.anchor.dayOfMonth,
            )
            else -> getString(
                R.string.report_range_day,
                selection.anchor.year,
                selection.anchor.monthValue,
                selection.anchor.dayOfMonth,
            )
        }
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
    data object Controls : ReportItem()
    data class Heatmap(val title: String, val data: HeatmapData) : ReportItem()
    data class LineChart(
        val title: String,
        val points: List<LineChartView.TimePoint>,
    ) : ReportItem()
    data class Section(val title: String) : ReportItem()
    data class TagRow(val row: ReportRow) : ReportItem()
    data class SessionRow(val session: SessionEntity, val colorArgb: Long) : ReportItem()
}

private class ReportAdapter(
    private val controlsHeader: View?,
    private val onPreviousHeatmapPage: () -> Unit,
    private val onNextHeatmapPage: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var items: List<ReportItem> = emptyList()

    fun submit(newItems: List<ReportItem>) {
        items = if (controlsHeader == null) newItems else listOf(ReportItem.Controls) + newItems
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        ReportItem.Controls -> TYPE_CONTROLS
        is ReportItem.Heatmap -> TYPE_HEATMAP
        is ReportItem.LineChart -> TYPE_LINECHART
        is ReportItem.Section -> TYPE_SECTION
        is ReportItem.TagRow -> TYPE_TAG
        is ReportItem.SessionRow -> TYPE_SESSION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_CONTROLS -> object : RecyclerView.ViewHolder(requireNotNull(controlsHeader)) {}
            TYPE_HEATMAP -> HeatmapViewHolder(
                inflater.inflate(R.layout.item_chart_heatmap, parent, false),
                onPreviousHeatmapPage,
                onNextHeatmapPage,
            )
            TYPE_LINECHART -> LineChartViewHolder(
                inflater.inflate(R.layout.item_chart_linechart, parent, false),
            )
            TYPE_SECTION -> SectionViewHolder(
                inflater.inflate(R.layout.item_report_section, parent, false),
            )
            TYPE_TAG -> TagViewHolder(inflater.inflate(R.layout.item_report, parent, false))
            else -> SessionViewHolder(
                inflater.inflate(R.layout.item_report_session, parent, false),
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            ReportItem.Controls -> Unit
            is ReportItem.Heatmap -> (holder as HeatmapViewHolder).bind(item)
            is ReportItem.LineChart -> (holder as LineChartViewHolder).bind(item)
            is ReportItem.Section -> (holder as SectionViewHolder).bind(item)
            is ReportItem.TagRow -> (holder as TagViewHolder).bind(item.row)
            is ReportItem.SessionRow -> (holder as SessionViewHolder).bind(
                item.session, item.colorArgb,
            )
        }
    }

    private companion object {
        const val TYPE_CONTROLS = 5
        const val TYPE_HEATMAP = 0
        const val TYPE_LINECHART = 1
        const val TYPE_SECTION = 2
        const val TYPE_TAG = 3
        const val TYPE_SESSION = 4
    }
}

private class HeatmapViewHolder(
    view: View,
    private val onPreviousPage: () -> Unit,
    private val onNextPage: () -> Unit,
) : RecyclerView.ViewHolder(view) {
    private val title: TextView = view.findViewById(R.id.chart_title)
    private val heatmap: HeatmapView = view.findViewById(R.id.chart_heatmap)
    private val pagePrevious: ImageButton = view.findViewById(R.id.heatmap_page_previous)
    private val pageTitle: TextView = view.findViewById(R.id.heatmap_page_title)
    private val pageNext: ImageButton = view.findViewById(R.id.heatmap_page_next)

    fun bind(item: ReportItem.Heatmap) {
        title.text = item.title
        pageTitle.text = item.data.pageTitle
        pagePrevious.isEnabled = item.data.previousEnabled
        pagePrevious.alpha = if (item.data.previousEnabled) 1f else 0.28f
        pageNext.isEnabled = item.data.nextEnabled
        pageNext.alpha = if (item.data.nextEnabled) 1f else 0.28f
        pagePrevious.setOnClickListener { onPreviousPage() }
        pageNext.setOnClickListener { onNextPage() }
        heatmap.setData(
            item.data.activityData,
            item.data.rangeStart,
            item.data.days,
            item.data.visibleThrough,
        )
    }
}

private data class HeatmapData(
    val activityData: Map<Int, Int>,
    val rangeStart: LocalDate,
    val days: Int,
    val visibleThrough: LocalDate,
    val pageTitle: String,
    val previousEnabled: Boolean,
    val nextEnabled: Boolean,
)

private class LineChartViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val title: TextView = view.findViewById(R.id.chart_title)
    private val linechart: LineChartView = view.findViewById(R.id.chart_linechart)

    fun bind(item: ReportItem.LineChart) {
        title.text = item.title
        linechart.setTimeData(item.points)
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
