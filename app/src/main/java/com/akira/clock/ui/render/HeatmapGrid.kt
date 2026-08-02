package com.akira.clock.ui.render

import java.time.LocalDate

/** Calendar-week positioning rules shared by the GitHub-style heatmap renderer and its tests. */
internal object HeatmapGrid {
    private const val DAYS_PER_WEEK = 7

    fun columnCount(rangeStart: LocalDate, days: Int): Int {
        val cells = leadingEmptyCells(rangeStart) + days.coerceAtLeast(1)
        return (cells + DAYS_PER_WEEK - 1) / DAYS_PER_WEEK
    }

    fun positionFor(rangeStart: LocalDate, dayIndex: Int): GridPosition {
        require(dayIndex >= 0) { "dayIndex must not be negative." }
        val cellIndex = leadingEmptyCells(rangeStart) + dayIndex
        return GridPosition(cellIndex / DAYS_PER_WEEK, cellIndex % DAYS_PER_WEEK)
    }

    private fun leadingEmptyCells(rangeStart: LocalDate): Int = rangeStart.dayOfWeek.value % DAYS_PER_WEEK
}

/** A zero-based Sunday-first grid coordinate. */
internal data class GridPosition(val column: Int, val row: Int)
