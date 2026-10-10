package com.akira.clock.habit

import com.akira.clock.data.HabitCheckInEntity
import com.akira.clock.data.HabitDao
import com.akira.clock.data.HabitEntity
import com.akira.clock.data.HabitTargetEntity
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HabitRepositoryTest {
    private val clock = MutableClock(Instant.parse("2026-10-04T00:30:00Z").toEpochMilli())
    private var zone = ZoneId.of("Asia/Shanghai")
    private val dao = MemoryHabitDao()
    private var identifier = 1L
    private val mutex = Mutex()
    private val repository = HabitRepository(
        dao = dao,
        clock = clock,
        zoneProvider = { zone },
        idSource = { UUID(0L, identifier++).toString() },
        transaction = HabitTransaction { action -> mutex.withLock { action() } },
    )
    private val today = LocalDate.of(2026, 10, 4)

    @Test
    fun createStartsOnInjectedLocalTodayWithNoTimerRelationship() = runBlocking {
        val habit = repository.create("  Read ×2  ")
        assertEquals("Read ×2", habit.name)
        assertEquals("2026-10-04", habit.createdLocalDateIso)
        assertEquals(0, habit.sortOrder)
        assertEquals(clock.millis(), habit.createdAtUtcMs)
        assertEquals(habit.createdAtUtcMs, habit.updatedAtUtcMs)
        assertNull(habit.archivedLocalDateIso)
        assertEquals(emptyList<HabitCheckInEntity>(), dao.getAllCheckIns())
    }

    @Test
    fun notesAreNormalizedEditedClearedAndKeepCheckIns() = runBlocking {
        val habit = repository.create("Read", "  Ten pages\nBefore bed  ")
        assertEquals("Ten pages\nBefore bed", habit.note)
        repository.setCompleted(habit.id, today, true)
        val checkIn = dao.getAllCheckIns().single()
        repository.update(habit.id, "Study", "  Review  ")
        val updated = dao.get(habit.id)!!
        assertEquals("Study", updated.name)
        assertEquals("Review", updated.note)
        assertEquals(habit.updatedAtUtcMs + 1, updated.updatedAtUtcMs)
        repository.update(habit.id, "Study", "Review")
        assertEquals(updated, dao.get(habit.id))
        repository.rename(habit.id, "Learn")
        assertEquals("Review", dao.get(habit.id)?.note)
        repository.update(habit.id, "Learn", "  ")
        assertEquals("", dao.get(habit.id)?.note)
        assertEquals(listOf(checkIn), dao.getAllCheckIns())
        expectInvalid { repository.create("Walk", "x".repeat(1001)) }
        expectInvalid { repository.update(habit.id, "Invalid", "x".repeat(1001)) }
        assertEquals("Learn", dao.get(habit.id)?.name)
    }

    @Test
    fun newHabitUsesChangedZoneButExistingDayKeysRemainStable() = runBlocking {
        val shanghai = repository.create("Read")
        zone = ZoneId.of("America/Los_Angeles")
        val losAngeles = repository.create("Walk")
        assertEquals("2026-10-04", dao.get(shanghai.id)?.createdLocalDateIso)
        assertEquals("2026-10-03", losAngeles.createdLocalDateIso)
        expectInvalid { repository.setCompleted(losAngeles.id, today, true) }
        repository.setCompleted(losAngeles.id, today.minusDays(1), true)
        assertEquals("2026-10-03", dao.getAllCheckIns().single().localDateIso)
    }

    @Test
    fun renamingIsValidatedAndRepeatedSameNameDoesNotWrite() = runBlocking {
        val habit = repository.create("Read")
        repository.rename(habit.id, "  Study  ")
        val renamed = dao.get(habit.id)!!
        assertEquals("Study", renamed.name)
        assertEquals(habit.updatedAtUtcMs + 1, renamed.updatedAtUtcMs)
        repository.rename(habit.id, "Study")
        assertEquals(renamed, dao.get(habit.id))
        expectInvalid { repository.rename(habit.id, "   ") }
        expectInvalid { repository.create("x".repeat(121)) }
        assertEquals(1, dao.getAll().size)
    }

    @Test
    fun checkInAndUndoAreIdempotentAndUndoPersistsANewerTombstone() = runBlocking {
        val habit = repository.create("Read")
        repository.setCompleted(habit.id, today, true)
        val completed = dao.getAllCheckIns().single()
        repository.setCompleted(habit.id, today, true)
        assertEquals(completed, dao.getAllCheckIns().single())
        repository.setCompleted(habit.id, today, false)
        val undone = dao.getAllCheckIns().single()
        assertFalse(undone.completed)
        assertNull(undone.completedAtUtcMs)
        assertEquals(completed.updatedAtUtcMs + 1, undone.updatedAtUtcMs)
        repository.setCompleted(habit.id, today, false)
        assertEquals(undone, dao.getAllCheckIns().single())
        repository.setCompleted(habit.id, today, true)
        assertEquals(undone.updatedAtUtcMs + 1, dao.getAllCheckIns().single().updatedAtUtcMs)
    }

    @Test
    fun explicitIncompleteStateCreatesOnlyOneTombstone() = runBlocking {
        val habit = repository.create("Read")
        repository.setCompleted(habit.id, today, false)
        repository.setCompleted(habit.id, today, false)
        assertEquals(1, dao.getAllCheckIns().size)
        assertFalse(dao.getAllCheckIns().single().completed)
        assertNull(dao.getAllCheckIns().single().completedAtUtcMs)
    }

    @Test
    fun rapidAndBackwardsClockUpdatesKeepUndoNewerThanCompletion() = runBlocking {
        val habit = repository.create("Read")
        repository.setCompleted(habit.id, today, true)
        val completed = dao.getAllCheckIns().single()
        clock.currentUtcMs -= 1_000
        repository.setCompleted(habit.id, today, false)
        assertEquals(completed.updatedAtUtcMs + 1, dao.getAllCheckIns().single().updatedAtUtcMs)
        repository.rename(habit.id, "Study")
        assertEquals(habit.updatedAtUtcMs + 1, dao.get(habit.id)?.updatedAtUtcMs)
    }

    @Test
    fun rejectsFuturePreCreationAndMissingHabitBeforeAnyCheckInWrite() = runBlocking {
        val habit = repository.create("Read")
        expectInvalid { repository.setCompleted(habit.id, today.plusDays(1), true) }
        expectInvalid { repository.setCompleted(habit.id, today.minusDays(1), true) }
        expectInvalid { repository.setCompleted("missing", today, true) }
        assertTrue(dao.getAllCheckIns().isEmpty())
    }

    @Test
    fun archivedHabitRetainsHistoryAndAllowsEligibleHistoricalUndo() = runBlocking {
        clock.currentUtcMs = Instant.parse("2026-10-02T01:00:00Z").toEpochMilli()
        val habit = repository.create("Read")
        repository.setCompleted(habit.id, today.minusDays(2), true)
        clock.currentUtcMs = Instant.parse("2026-10-04T01:00:00Z").toEpochMilli()
        repository.setCompleted(habit.id, today, true)
        repository.archive(habit.id)
        val archived = dao.get(habit.id)!!
        assertEquals(today.toString(), archived.archivedLocalDateIso)
        assertEquals(2, dao.getAllCheckIns().size)
        assertTrue(HabitPolicy.isEligible(archived, today))
        assertTrue(HabitPolicy.isEligible(archived, today.minusDays(1)))
        expectInvalid { repository.setCompleted(habit.id, today, false) }
        repository.setCompleted(habit.id, today.minusDays(2), false)
        assertFalse(dao.getCheckIn(habit.id, today.minusDays(2).toString())!!.completed)
        repository.archive(habit.id)
        assertEquals(archived, dao.get(habit.id))
    }

    @Test
    fun createCompleteAndArchiveSameDayRemainsAccessibleOnTomorrowHistory() = runBlocking {
        val habit = repository.create("Read")
        repository.setCompleted(habit.id, today, true)
        repository.archive(habit.id)
        val sameDay = repository.observe(today, today).first()
        assertTrue(HabitPolicy.visibleHabits(sameDay, today, today).isEmpty())
        expectInvalid { repository.setCompleted(habit.id, today, false) }
        clock.currentUtcMs = Instant.parse("2026-10-05T01:00:00Z").toEpochMilli()
        val tomorrow = today.plusDays(1)
        val historical = repository.observe(today, tomorrow).first()
        assertEquals(listOf(habit.id),
            HabitPolicy.visibleHabits(historical, today, tomorrow).map { it.id })
        assertEquals(HabitDayState.COMPLETED, HabitPolicy.stateFor(
            historical.habits.single(), today, tomorrow, historical.checkIns,
        ))
        repository.setCompleted(habit.id, today, false)
        assertFalse(dao.getAllCheckIns().single().completed)
        expectInvalid { repository.setCompleted(habit.id, tomorrow, true) }
    }

    @Test
    fun archiveCannotMoveBeforeCreationWhenTimeZoneMovesBackwards() = runBlocking {
        val habit = repository.create("Read")
        zone = ZoneId.of("America/Los_Angeles")
        repository.archive(habit.id)
        assertEquals(habit.createdLocalDateIso, dao.get(habit.id)?.archivedLocalDateIso)
    }

    @Test
    fun adjacentMovesAreAtomicAndStayWithinActiveHabits() = runBlocking {
        val first = repository.create("First")
        val second = repository.create("Second")
        val third = repository.create("Third")
        repository.move(first.id, -1)
        assertEquals(listOf(first.id, second.id, third.id), activeIds())
        repository.move(third.id, -1)
        assertEquals(listOf(first.id, third.id, second.id), activeIds())
        repository.archive(third.id)
        repository.move(first.id, 1)
        assertEquals(listOf(second.id, first.id), activeIds())
        expectInvalid { repository.move(third.id, 1) }
        expectInvalid { repository.move(first.id, 0) }
    }

    @Test
    fun importedDuplicateSortOrdersBecomeDistinctOnMove() = runBlocking {
        val first = repository.create("First")
        val second = repository.create("Second")
        val third = repository.create("Third")
        dao.upsertHabits(listOf(second.copy(sortOrder = 0), third.copy(sortOrder = 0)))
        repository.move(first.id, 1)
        assertEquals(listOf(second.id, first.id, third.id), activeIds())
        assertEquals(listOf(0, 1, 2), dao.getAll().map { it.sortOrder })
    }

    @Test
    fun observationFiltersCheckInsByInclusiveLocalDates() = runBlocking {
        clock.currentUtcMs = Instant.parse("2026-10-02T01:00:00Z").toEpochMilli()
        val habit = repository.create("Read")
        clock.currentUtcMs = Instant.parse("2026-10-04T01:00:00Z").toEpochMilli()
        for (offset in 0L..2L) repository.setCompleted(habit.id, today.minusDays(offset), true)
        val snapshot = repository.observe(today.minusDays(1), today).first()
        assertEquals(listOf(habit.id), snapshot.habits.map { it.id })
        assertEquals(listOf("2026-10-03", "2026-10-04"),
            snapshot.checkIns.map { it.localDateIso })
        expectInvalid { repository.observe(today, today.minusDays(1)) }
    }

    @Test
    fun concurrentRepeatedCompletionUsesOnePersistentKeyAndOneWrite() = runBlocking {
        val habit = repository.create("Read")
        (1..50).map {
            async(Dispatchers.Default) { repository.setCompleted(habit.id, today, true) }
        }.awaitAll()
        assertEquals(1, dao.getAllCheckIns().size)
        assertEquals(1, dao.checkInWrites.size)
        assertTrue(dao.getAllCheckIns().single().completed)
    }

    @Test
    fun concurrentDifferentCommandsKeepStrictlyOrderedPersistentVersions() = runBlocking {
        val habit = repository.create("Read")
        (1..50).map { index ->
            async(Dispatchers.Default) {
                repository.setCompleted(habit.id, today, index % 2 == 0)
            }
        }.awaitAll()
        assertEquals(1, dao.getAllCheckIns().size)
        assertTrue(dao.checkInWrites.zipWithNext().all { (earlier, later) ->
            earlier.updatedAtUtcMs < later.updatedAtUtcMs
        })
        assertEquals(dao.checkInWrites.last(), dao.getAllCheckIns().single())
    }

    @Test
    fun repetitionsCompleteOnlyAtGoalAndUndoOneAtATime() = runBlocking {
        val habit = repository.create("Exercise", targetCount = 2)
        val first = repository.record(habit.id, today)
        assertEquals(1, first.checkIn.completedCount)
        assertFalse(first.checkIn.completed)
        val second = repository.record(habit.id, today)
        assertEquals(2, second.checkIn.completedCount)
        assertTrue(second.checkIn.completed)
        assertEquals(1, repository.record(habit.id, today).checkIn.completedCount)
        repository.undoOnce(habit.id, today)
        assertEquals(0, dao.getAllCheckIns().single().completedCount)
        repository.undoOnce(habit.id, today)
        assertEquals(0, dao.getAllCheckIns().single().completedCount)
    }

    @Test
    fun staleFeedbackUndoCannotRemoveALaterRepetition() = runBlocking {
        val habit = repository.create("Exercise", targetCount = 3)
        val first = repository.record(habit.id, today)
        repository.record(habit.id, today)
        repository.undoOnce(habit.id, today, first.checkIn.updatedAtUtcMs)
        assertEquals(2, dao.getAllCheckIns().single().completedCount)
    }

    @Test
    fun goalEditsKeepPartialCountsAndHistoricalTargetsIncludingUnrecordedDays() = runBlocking {
        val habit = repository.create("Exercise", targetCount = 2)
        repository.record(habit.id, today)
        repository.update(habit.id, habit.name, "", 3)
        assertEquals(1, dao.getAllCheckIns().single().completedCount)
        clock.currentUtcMs += 86_400_000
        repository.update(habit.id, habit.name, "", 1)
        val snapshot = repository.observe(today, today.plusDays(1)).first()
        assertEquals(3, HabitPolicy.targetFor(habit.id, today, snapshot.targets))
        assertEquals(1, HabitPolicy.targetFor(habit.id, today.plusDays(1), snapshot.targets))
        assertEquals(0, HabitPolicy.countFor(habit.id, today.plusDays(1), snapshot.checkIns))
        assertEquals(HabitDayState.INCOMPLETE, HabitPolicy.stateFor(
            habit, today, today.plusDays(1), snapshot.checkIns, snapshot.targets,
        ))
        repository.record(habit.id, today)
        assertEquals(2, dao.getCheckIn(habit.id, today.toString())!!.completedCount)
        expectInvalid { repository.create("Invalid", targetCount = 0) }
        expectInvalid { repository.update(habit.id, habit.name, "", 100) }
    }

    @Test
    fun loweringTargetKeepsRecordedCountAndIncreasingReopensToday() = runBlocking {
        val habit = repository.create("Exercise", targetCount = 3)
        repeat(2) { repository.record(habit.id, today) }
        repository.update(habit.id, habit.name, "", 1)
        assertTrue(dao.getAllCheckIns().single().completed)
        assertEquals(2, dao.getAllCheckIns().single().completedCount)
        repository.update(habit.id, habit.name, "", 3)
        assertFalse(dao.getAllCheckIns().single().completed)
        assertNull(dao.getAllCheckIns().single().completedAtUtcMs)
    }

    private suspend fun activeIds(): List<String> =
        dao.getAll().filter { it.archivedLocalDateIso == null }.map { it.id }

    private suspend fun expectInvalid(action: suspend () -> Unit) {
        try {
            action()
            fail("Expected argument validation failure")
        } catch (_: IllegalArgumentException) {
            // The assertion checks that invalid commands fail before the fake store changes.
        }
    }

    private class MutableClock(var currentUtcMs: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
        override fun instant(): Instant = Instant.ofEpochMilli(currentUtcMs)
    }

    private class MemoryHabitDao : HabitDao {
        private val targets = MutableStateFlow<List<HabitTargetEntity>>(emptyList())
        private val habits = MutableStateFlow<List<HabitEntity>>(emptyList())
        private val checkIns = MutableStateFlow<List<HabitCheckInEntity>>(emptyList())
        val checkInWrites = mutableListOf<HabitCheckInEntity>()

        override fun observeTargets(): Flow<List<HabitTargetEntity>> = targets
        override suspend fun getAllTargets(): List<HabitTargetEntity> = targets.value
        override suspend fun upsertTargets(targets: List<HabitTargetEntity>) {
            val byKey = this.targets.value.associateBy {
                it.habitId to it.effectiveLocalDateIso
            }.toMutableMap()
            targets.forEach { byKey[it.habitId to it.effectiveLocalDateIso] = it }
            this.targets.value = byKey.values.toList()
        }

        override fun observeHabits(): Flow<List<HabitEntity>> = habits

        override fun observeCheckIns(start: String, end: String): Flow<List<HabitCheckInEntity>> =
            checkIns.map { rows -> rows.filter { it.localDateIso in start..end } }

        override suspend fun getAll(): List<HabitEntity> = habits.value
        override suspend fun getAllCheckIns(): List<HabitCheckInEntity> = checkIns.value
        override suspend fun get(id: String): HabitEntity? = habits.value.find { it.id == id }

        override suspend fun getCheckIn(habitId: String, date: String): HabitCheckInEntity? =
            checkIns.value.find { it.habitId == habitId && it.localDateIso == date }

        override suspend fun upsertHabits(habits: List<HabitEntity>) {
            val byId = this.habits.value.associateBy { it.id }.toMutableMap()
            habits.forEach { byId[it.id] = it }
            this.habits.value = byId.values.sortedWith(HabitPolicy.displayOrder)
        }

        override suspend fun upsertCheckIns(checkIns: List<HabitCheckInEntity>) {
            val byKey = this.checkIns.value.associateBy {
                it.habitId to it.localDateIso
            }.toMutableMap()
            checkIns.forEach {
                byKey[it.habitId to it.localDateIso] = it
                checkInWrites += it
            }
            this.checkIns.value = byKey.values.sortedWith(
                compareBy({ it.localDateIso }, { it.habitId }),
            )
        }
    }
}
