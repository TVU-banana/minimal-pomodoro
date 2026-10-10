package com.akira.clock.habit

import com.akira.clock.data.HabitCheckInEntity
import com.akira.clock.data.HabitEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitPolicyTest {
    private val today = LocalDate.of(2026, 10, 4)
    private val habit = HabitEntity(
        id = "00000000-0000-4000-8000-000000000001",
        name = "Read",
        sortOrder = 0,
        createdLocalDateIso = "2026-10-02",
        createdAtUtcMs = 100,
        updatedAtUtcMs = 100,
    )

    @Test
    fun distinguishesApplicableMissingFutureAndPreCreationDays() {
        assertEquals(HabitDayState.INCOMPLETE, state(today))
        assertEquals(HabitDayState.INCOMPLETE, state(today.minusDays(2)))
        assertEquals(HabitDayState.NOT_APPLICABLE, state(today.minusDays(3)))
        assertEquals(HabitDayState.FUTURE, state(today.plusDays(1)))
    }

    @Test
    fun completionIsKeyedByHabitAndLocalDateRatherThanAuditTimestamp() {
        val checkIn = HabitCheckInEntity(habit.id, today.toString(), true, 1, 100)
        assertEquals(HabitDayState.COMPLETED, state(today, listOf(checkIn)))
        assertEquals(HabitDayState.INCOMPLETE, state(today.minusDays(1), listOf(checkIn)))
        val otherHabit = checkIn.copy(habitId = "another")
        assertEquals(HabitDayState.INCOMPLETE, state(today, listOf(otherHabit)))
        assertEquals(
            HabitDayState.INCOMPLETE,
            state(
                today,
                listOf(
                    checkIn.copy(
                        completed = false,
                        completedAtUtcMs = null,
                        completedCount = 0,
                    )
                ),
            ),
        )
    }

    @Test
    fun archiveDateRetainsCompletionHistoryButArchiveHidesOnCurrentDay() {
        val archived = habit.copy(archivedLocalDateIso = today.toString())
        val retained = HabitCheckInEntity(habit.id, today.toString(), true, 100, 100)
        assertTrue(HabitPolicy.isEligible(archived, today.minusDays(1)))
        assertTrue(HabitPolicy.isEligible(archived, today))
        assertFalse(HabitPolicy.isEligible(archived, today.plusDays(1)))
        assertEquals(
            HabitDayState.COMPLETED,
            HabitPolicy.stateFor(archived, today, today, listOf(retained)),
        )
        assertEquals(
            listOf(archived),
            HabitPolicy.visibleHabits(
                HabitSnapshot(listOf(archived), listOf(retained)),
                today.minusDays(1),
                today,
            ),
        )
        assertTrue(HabitPolicy.visibleHabits(
            HabitSnapshot(listOf(archived), listOf(retained)), today, today,
        ).isEmpty())
    }

    @Test
    fun sameDayArchiveRetainsItsCreationDayHistory() {
        val archived = habit.copy(archivedLocalDateIso = habit.createdLocalDateIso)
        assertTrue(HabitPolicy.isEligible(archived, LocalDate.parse(habit.createdLocalDateIso)))
        val created = LocalDate.parse(habit.createdLocalDateIso)
        assertEquals(listOf(archived), HabitPolicy.visibleHabits(
            HabitSnapshot(listOf(archived), emptyList()), created, created.plusDays(1),
        ))
    }

    @Test
    fun leapDayMonthAndYearBoundariesUseCalendarDates() {
        val leap = habit.copy(
            createdLocalDateIso = "2024-02-29", archivedLocalDateIso = "2024-03-01",
        )
        assertTrue(HabitPolicy.isEligible(leap, LocalDate.of(2024, 2, 29)))
        assertFalse(HabitPolicy.isEligible(leap, LocalDate.of(2024, 2, 28)))
        assertTrue(HabitPolicy.isEligible(leap, LocalDate.of(2024, 3, 1)))
        assertFalse(HabitPolicy.isEligible(leap, LocalDate.of(2024, 3, 2)))
        val newYear = habit.copy(createdLocalDateIso = "2025-12-31")
        assertTrue(HabitPolicy.isEligible(newYear, LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun timeZoneChangesResolveCurrentDateWithoutRewritingOldKeys() {
        val now = Instant.parse("2026-10-04T00:30:00Z").toEpochMilli()
        assertEquals(today, HabitPolicy.localDateAt(now, ZoneId.of("Asia/Shanghai")))
        assertEquals(today.minusDays(1),
            HabitPolicy.localDateAt(now, ZoneId.of("America/Los_Angeles")))
    }

    @Test
    fun dstDaysKeepTheirLocalCalendarIdentity() {
        val newYork = ZoneId.of("America/New_York")
        for (dateText in listOf("2026-03-08", "2026-11-01")) {
            val date = LocalDate.parse(dateText)
            val start = date.atStartOfDay(newYork).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(newYork).toInstant().toEpochMilli()
            assertEquals(date, HabitPolicy.localDateAt(start, newYork))
            assertEquals(date, HabitPolicy.localDateAt(end - 1, newYork))
            assertEquals(date.plusDays(1), HabitPolicy.localDateAt(end, newYork))
            assertTrue(end - start != 24 * 60 * 60_000L)
        }
    }

    @Test
    fun nameNormalizationKeepsLiteralCountHintsAndTrimsWhitespace() {
        assertEquals("Water ×2", HabitPolicy.normalizeName("  Water ×2  "))
        assertEquals("x".repeat(120), HabitPolicy.normalizeName("x".repeat(120)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankNameIsRejected() {
        HabitPolicy.normalizeName(" \n ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun overlongNameIsRejected() {
        HabitPolicy.normalizeName("x".repeat(121))
    }

    @Test
    fun displayOrderHasStableTiesAndHidesArchivedHabitsOnToday() {
        val first = habit.copy(id = "a", sortOrder = 1)
        val second = habit.copy(id = "b", sortOrder = 1)
        val earlier = habit.copy(id = "c", sortOrder = 0)
        val archived = habit.copy(id = "d", archivedLocalDateIso = today.toString())
        val snapshot = HabitSnapshot(listOf(second, archived, first, earlier), emptyList())
        assertEquals(listOf("c", "a", "b"),
            HabitPolicy.visibleHabits(snapshot, today, today).map { it.id })
        assertTrue(HabitPolicy.visibleHabits(snapshot, today.minusDays(5), today).isEmpty())
    }

    @Test
    fun newHabitAppearsOnCreationDayWithoutChangingEarlierHistory() {
        val created = LocalDate.of(2026, 10, 5)
        val existing = habit.copy(id = "existing", sortOrder = 1)
        val newHabit = habit.copy(id = "new", createdLocalDateIso = created.toString())
        val snapshot = HabitSnapshot(listOf(existing, newHabit), emptyList())

        assertEquals(listOf(existing), HabitPolicy.visibleHabits(
            snapshot, created.minusDays(1), created,
        ))
        assertEquals(listOf(newHabit, existing), HabitPolicy.visibleHabits(
            snapshot, created, created,
        ))
        assertEquals(listOf(newHabit, existing), HabitPolicy.visibleHabits(
            snapshot, created.plusDays(1), created.plusDays(1),
        ))
        val archived = newHabit.copy(archivedLocalDateIso = created.plusDays(1).toString())
        val archivedSnapshot = HabitSnapshot(listOf(existing, archived), emptyList())
        assertEquals(listOf(existing), HabitPolicy.visibleHabits(
            archivedSnapshot, created.minusDays(1), created.plusDays(2),
        ))
        assertEquals(listOf(archived, existing), HabitPolicy.visibleHabits(
            archivedSnapshot, created, created.plusDays(2),
        ))
    }

    @Test
    fun updatesAreMonotonicEvenWhenClockCollidesOrMovesBackwards() {
        assertEquals(1_000L, HabitPolicy.nextUpdatedAt(1_000, null))
        assertEquals(1_001L, HabitPolicy.nextUpdatedAt(1_000, 1_000))
        assertEquals(1_001L, HabitPolicy.nextUpdatedAt(900, 1_000))
        assertEquals(2_000L, HabitPolicy.nextUpdatedAt(2_000, 1_000))
    }

    private fun state(
        date: LocalDate,
        checkIns: List<HabitCheckInEntity> = emptyList(),
    ): HabitDayState = HabitPolicy.stateFor(habit, date, today, checkIns)
}
