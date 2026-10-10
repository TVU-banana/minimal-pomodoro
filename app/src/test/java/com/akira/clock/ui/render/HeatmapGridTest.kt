package com.akira.clock.ui.render

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatmapGridTest {
    @Test
    fun placesMondayAtTheSecondGitHubStyleRow() {
        val position = HeatmapGrid.positionFor(LocalDate.of(2026, 7, 6), 0)

        assertEquals(GridPosition(column = 0, row = 1), position)
    }

    @Test
    fun keepsAThursdayMonthStartInItsCalendarWeek() {
        val start = LocalDate.of(2026, 10, 1)
        val firstDay = HeatmapGrid.positionFor(start, 0)
        val fourthDay = HeatmapGrid.positionFor(start, 3)

        assertEquals(GridPosition(column = 0, row = 4), firstDay)
        assertEquals(GridPosition(column = 1, row = 0), fourthDay)
        assertEquals(2, HeatmapGrid.columnCount(start, 4))
    }

    @Test
    fun labelsTheStartMonthWhenAllTimeDataBeginsMidMonth() {
        val start = LocalDate.of(2026, 8, 5)

        assertTrue(HeatmapGrid.showsMonthLabel(start, 0))
        assertFalse(HeatmapGrid.showsMonthLabel(start, 1))
    }
}
