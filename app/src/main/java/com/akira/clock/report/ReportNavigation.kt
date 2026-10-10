package com.akira.clock.report

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Immutable report scope selected by the user. */
internal data class ReportSelection(
    val period: ReportPeriod,
    val anchor: LocalDate,
)

/** One fixed, three-month page within a selected report year. */
internal data class HeatmapPage(
    val year: Int,
    val pageIndex: Int,
    val start: LocalDate,
    val endExclusive: LocalDate,
)

/** Pure calendar rules for report range navigation and yearly heatmap pages. */
internal object ReportNavigation {
    private const val MONTHS_PER_PAGE = 3
    private const val PAGE_COUNT = 4

    fun currentSelection(period: ReportPeriod, today: LocalDate): ReportSelection {
        requireSupported(period)
        return ReportSelection(period, today)
    }

    fun previous(selection: ReportSelection): ReportSelection = selection.copy(
        anchor = when (selection.period) {
            ReportPeriod.YEAR -> selection.anchor.minusYears(1)
            ReportPeriod.WEEK -> selection.anchor.minusWeeks(1)
            ReportPeriod.DAY -> selection.anchor.minusDays(1)
        },
    )

    fun nextOrNull(selection: ReportSelection, today: LocalDate): ReportSelection? {
        requireSupported(selection.period)
        val candidate = when (selection.period) {
            ReportPeriod.YEAR -> selection.anchor.plusYears(1)
            ReportPeriod.WEEK -> selection.anchor.plusWeeks(1)
            ReportPeriod.DAY -> selection.anchor.plusDays(1)
        }
        return candidate.takeIf { isCurrentOrPast(selection.period, it, today) }
            ?.let { selection.copy(anchor = it) }
    }

    fun normalize(selection: ReportSelection, today: LocalDate): ReportSelection {
        requireSupported(selection.period)
        return if (isCurrentOrPast(selection.period, selection.anchor, today)) {
            selection
        } else {
            currentSelection(selection.period, today)
        }
    }

    fun pageIndexFor(anchor: LocalDate): Int = (anchor.monthValue - 1) / MONTHS_PER_PAGE

    fun heatmapPage(selection: ReportSelection, pageIndex: Int): HeatmapPage {
        require(selection.period == ReportPeriod.YEAR) {
            "Heatmap pages require a yearly selection."
        }
        require(pageIndex in 0 until PAGE_COUNT) { "pageIndex must be between 0 and 3." }
        val start = LocalDate.of(selection.anchor.year, pageIndex * MONTHS_PER_PAGE + 1, 1)
        return HeatmapPage(
            year = selection.anchor.year,
            pageIndex = pageIndex,
            start = start,
            endExclusive = start.plusMonths(MONTHS_PER_PAGE.toLong()),
        )
    }

    fun previousHeatmapPageOrNull(page: HeatmapPage): HeatmapPage? =
        page.takeIf { it.pageIndex > 0 }?.let { heatmapPageFor(it.year, it.pageIndex - 1) }

    fun nextHeatmapPageOrNull(page: HeatmapPage, today: LocalDate): HeatmapPage? {
        if (page.pageIndex == PAGE_COUNT - 1) return null
        val candidate = heatmapPageFor(page.year, page.pageIndex + 1)
        return candidate.takeIf { !it.start.isAfter(today) }
    }

    fun weekStart(anchor: LocalDate): LocalDate =
        anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun heatmapPageFor(year: Int, pageIndex: Int): HeatmapPage {
        val start = LocalDate.of(year, pageIndex * MONTHS_PER_PAGE + 1, 1)
        return HeatmapPage(year, pageIndex, start, start.plusMonths(MONTHS_PER_PAGE.toLong()))
    }

    private fun isCurrentOrPast(
        period: ReportPeriod,
        anchor: LocalDate,
        today: LocalDate,
    ): Boolean = when (period) {
        ReportPeriod.YEAR -> anchor.year <= today.year
        ReportPeriod.WEEK -> weekStart(anchor) <= weekStart(today)
        ReportPeriod.DAY -> !anchor.isAfter(today)
    }

    private fun requireSupported(period: ReportPeriod) {
        require(
            period == ReportPeriod.YEAR ||
                period == ReportPeriod.WEEK ||
                period == ReportPeriod.DAY
        ) {
            "Unsupported report period: $period"
        }
    }
}
