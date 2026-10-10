package com.akira.clock.report

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReportNavigationTest {
    private val today = LocalDate.of(2026, 8, 11)

    @Test
    fun dayCannotNavigatePastTodayButYesterdayCanReturn() {
        val current = ReportNavigation.currentSelection(ReportPeriod.DAY, today)
        val yesterday = ReportNavigation.previous(current)

        assertNull(ReportNavigation.nextOrNull(current, today))
        assertEquals(current, ReportNavigation.nextOrNull(yesterday, today))
    }

    @Test
    fun weekUsesMondayBoundaryForFutureLimit() {
        val current = ReportNavigation.currentSelection(ReportPeriod.WEEK, today)
        val previous = ReportNavigation.previous(current)

        assertEquals(LocalDate.of(2026, 8, 10), ReportNavigation.weekStart(current.anchor))
        assertEquals(current, ReportNavigation.nextOrNull(previous, today))
        assertNull(ReportNavigation.nextOrNull(current, today))
    }

    @Test
    fun yearCannotNavigatePastCurrentYear() {
        val current = ReportNavigation.currentSelection(ReportPeriod.YEAR, today)
        val previous = ReportNavigation.previous(current)

        assertNull(ReportNavigation.nextOrNull(current, today))
        assertEquals(current, ReportNavigation.nextOrNull(previous, today))
    }

    @Test
    fun currentYearStartsOnTheQuarterContainingToday() {
        val selection = ReportNavigation.currentSelection(ReportPeriod.YEAR, today)
        val page = ReportNavigation.heatmapPage(
            selection,
            ReportNavigation.pageIndexFor(selection.anchor),
        )

        assertEquals(2, page.pageIndex)
        assertEquals(LocalDate.of(2026, 7, 1), page.start)
        assertEquals(LocalDate.of(2026, 10, 1), page.endExclusive)
    }

    @Test
    fun currentYearCannotNavigateToFutureQuarter() {
        val page = ReportNavigation.heatmapPage(
            ReportNavigation.currentSelection(ReportPeriod.YEAR, today),
            2,
        )

        assertNull(ReportNavigation.nextHeatmapPageOrNull(page, today))
    }

    @Test
    fun historicalYearCanNavigateToFourthQuarter() {
        val selection = ReportSelection(ReportPeriod.YEAR, LocalDate.of(2025, 8, 11))
        val thirdQuarter = ReportNavigation.heatmapPage(selection, 2)
        val fourthQuarter = requireNotNull(
            ReportNavigation.nextHeatmapPageOrNull(thirdQuarter, today),
        )

        assertEquals(3, fourthQuarter.pageIndex)
        assertEquals(LocalDate.of(2025, 10, 1), fourthQuarter.start)
    }

    @Test
    fun leapYearFirstPageEndsAfterFebruaryTwentyNinth() {
        val selection = ReportSelection(ReportPeriod.YEAR, LocalDate.of(2024, 6, 1))
        val page = ReportNavigation.heatmapPage(selection, 0)

        assertEquals(LocalDate.of(2024, 4, 1), page.endExclusive)
    }
}
