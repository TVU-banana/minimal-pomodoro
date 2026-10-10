package com.akira.clock.habit

import androidx.room.withTransaction
import com.akira.clock.data.ClockDatabase
import com.akira.clock.data.HabitCheckInEntity
import com.akira.clock.data.HabitDao
import com.akira.clock.data.HabitEntity
import com.akira.clock.data.HabitTargetEntity
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** The production implementation delegates to one Room transaction per user command. */
internal fun interface HabitTransaction {
    suspend fun run(action: suspend () -> Unit)
}

/** Revision returned to feedback so a delayed undo cannot remove a later repetition. */
internal data class HabitCountChange(
    val previousCount: Int,
    val checkIn: HabitCheckInEntity,
    val targetCount: Int,
)

/**
 * Owns independent habit mutations. Validation and read-modify-write share a transaction so rapid
 * commands preserve per-key timestamp ordering. Repeated state commands are idempotent; undo rows
 * remain persisted for backup conflict resolution. The injected calendar is resolved per command
 * so foreground date or time-zone changes affect new writes without rewriting earlier day keys.
 */
internal class HabitRepository internal constructor(
    private val dao: HabitDao,
    private val clock: Clock,
    private val zoneProvider: () -> ZoneId,
    private val idSource: () -> String,
    private val transaction: HabitTransaction,
) {
    constructor(
        database: ClockDatabase,
        clock: Clock = Clock.systemUTC(),
        zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
        idSource: () -> String = { UUID.randomUUID().toString() },
    ) : this(
        database.habitDao(),
        clock,
        zoneProvider,
        idSource,
        HabitTransaction { action -> database.withTransaction { action() } },
    )

    /** Inclusive local-date range; Room remains the source for every emitted state. */
    fun observe(start: LocalDate, end: LocalDate): Flow<HabitSnapshot> {
        require(start <= end) { "Invalid habit date range" }
        return combine(
            dao.observeHabits(),
            dao.observeCheckIns(start.toString(), end.toString()),
            dao.observeTargets(),
        ) { habits, checkIns, targets -> HabitSnapshot(habits, checkIns, targets) }
    }

    suspend fun create(name: String, note: String = "", targetCount: Int = 1): HabitEntity {
        HabitPolicy.requireTarget(targetCount)
        val normalized = HabitPolicy.normalizeName(name)
        val normalizedNote = HabitPolicy.normalizeNote(note)
        lateinit var created: HabitEntity
        transaction.run {
            val existing = dao.getAll()
            val highestOrder = existing.maxOfOrNull { it.sortOrder } ?: -1
            require(highestOrder < Int.MAX_VALUE) { "Habit ordering exhausted" }
            val id = idSource()
            val canonicalId = runCatching { UUID.fromString(id).toString() }.getOrNull()
            require(canonicalId == id && dao.get(id) == null) { "Invalid habit identifier" }
            val now = HabitPolicy.nextUpdatedAt(clock.millis(), null)
            created = HabitEntity(
                id = id,
                name = normalized,
                note = normalizedNote,
                sortOrder = highestOrder + 1,
                createdLocalDateIso = HabitPolicy.localDateAt(now, zoneProvider()).toString(),
                createdAtUtcMs = now,
                updatedAtUtcMs = now,
            )
            dao.upsertHabits(listOf(created))
            dao.upsertTargets(listOf(HabitTargetEntity(
                created.id, created.createdLocalDateIso, targetCount, now,
            )))
        }
        return created
    }

    suspend fun rename(id: String, name: String) = updateDetails(id, name, null)

    /** Name and note share one revision so backup merges cannot split a single editor save. */
    suspend fun update(id: String, name: String, note: String, targetCount: Int? = null) =
        updateDetails(id, name, note, targetCount)

    private suspend fun updateDetails(
        id: String, name: String, note: String?, targetCount: Int? = null,
    ) {
        targetCount?.let(HabitPolicy::requireTarget)
        val normalized = HabitPolicy.normalizeName(name)
        val normalizedNote = note?.let(HabitPolicy::normalizeNote)
        transaction.run {
            val existing = requireHabit(id)
            val updatedNote = normalizedNote ?: existing.note
            if (targetCount != null) {
                val date = maxOf(
                    HabitPolicy.localDateAt(clock.millis(), zoneProvider()),
                    LocalDate.parse(existing.createdLocalDateIso),
                )
                val targets = dao.getAllTargets()
                if (HabitPolicy.targetFor(id, date, targets) != targetCount) {
                    val previous = targets.find {
                        it.habitId == id && it.effectiveLocalDateIso == date.toString()
                    }
                    val revision = HabitPolicy.nextUpdatedAt(
                        clock.millis(), previous?.updatedAtUtcMs,
                    )
                    dao.upsertTargets(listOf(HabitTargetEntity(
                        id, date.toString(), targetCount, revision,
                    )))
                    dao.getCheckIn(id, date.toString())?.let {
                        writeCount(id, date, it.completedCount, targetCount, it)
                    }
                }
            }
            if (existing.name != normalized || existing.note != updatedNote) {
                dao.upsertHabits(
                    listOf(
                        existing.copy(
                            name = normalized,
                            note = updatedNote,
                            updatedAtUtcMs = HabitPolicy.nextUpdatedAt(
                                clock.millis(),
                                existing.updatedAtUtcMs,
                            ),
                        ),
                    ),
                )
            }
        }
    }

    /** Adjacent moves affect active habits and normalize duplicate order values from imports. */
    suspend fun move(id: String, direction: Int) {
        require(direction == -1 || direction == 1) { "Invalid habit move direction" }
        transaction.run {
            val ordered = dao.getAll().filter { it.archivedLocalDateIso == null }
                .sortedWith(HabitPolicy.displayOrder).toMutableList()
            val from = ordered.indexOfFirst { it.id == id }
            require(from >= 0) { "Active habit not found" }
            val to = from + direction
            if (to !in ordered.indices) return@run
            val moved = ordered.removeAt(from)
            ordered.add(to, moved)
            val now = clock.millis()
            val changed = ordered.mapIndexedNotNull { index, habit ->
                if (habit.sortOrder == index) null else habit.copy(
                    sortOrder = index,
                    updatedAtUtcMs = HabitPolicy.nextUpdatedAt(now, habit.updatedAtUtcMs),
                )
            }
            dao.upsertHabits(changed)
        }
    }

    /** Archiving is irreversible in this version and never deletes historical completion rows. */
    suspend fun archive(id: String) {
        transaction.run {
            val existing = requireHabit(id)
            if (existing.archivedLocalDateIso != null) return@run
            val now = clock.millis()
            val today = HabitPolicy.localDateAt(now, zoneProvider())
            val archiveDate = maxOf(today, LocalDate.parse(existing.createdLocalDateIso))
            dao.upsertHabits(
                listOf(
                    existing.copy(
                        archivedLocalDateIso = archiveDate.toString(),
                        updatedAtUtcMs = HabitPolicy.nextUpdatedAt(now, existing.updatedAtUtcMs),
                    ),
                ),
            )
        }
    }

    /** Legacy explicit state commands remain idempotent; UI records one repetition instead. */
    suspend fun setCompleted(id: String, date: LocalDate, completed: Boolean) {
        changeCount(id, date) { _, target -> if (completed) target else 0 }
    }

    /** Each accepted tap changes one repetition atomically; completed taps undo only one. */
    suspend fun record(id: String, date: LocalDate): HabitCountChange =
        changeCount(id, date) { count, target ->
            if (count >= target) count - 1 else count + 1
        }

    suspend fun undoOnce(id: String, date: LocalDate, expectedRevision: Long? = null) {
        changeCount(id, date, expectedRevision) { count, _ -> (count - 1).coerceAtLeast(0) }
    }

    private suspend fun changeCount(
        id: String, date: LocalDate, expectedRevision: Long? = null,
        transform: (Int, Int) -> Int,
    ): HabitCountChange {
        lateinit var result: HabitCountChange
        transaction.run {
            val habit = requireHabit(id)
            val today = HabitPolicy.localDateAt(clock.millis(), zoneProvider())
            require(
                date <= today && HabitPolicy.isEligible(habit, date) &&
                    (habit.archivedLocalDateIso == null || date < today),
            ) { "Habit day cannot be edited" }
            val existing = dao.getCheckIn(id, date.toString())
            val target = HabitPolicy.targetFor(id, date, dao.getAllTargets())
            val previousCount = existing?.completedCount ?: 0
            val matches = expectedRevision == null || existing?.updatedAtUtcMs == expectedRevision
            val count = if (matches) {
                transform(previousCount, target)
            } else previousCount
            writeCount(id, date, count, target, existing)
            result = HabitCountChange(previousCount,
                requireNotNull(dao.getCheckIn(id, date.toString())), target)
        }
        return result
    }

    /** Count and completion cache share one transaction; zero rows retain backup undo revisions. */
    private suspend fun writeCount(
        id: String, date: LocalDate, count: Int, target: Int, existing: HabitCheckInEntity?,
    ) {
        val completed = count >= target
        if (existing?.completedCount == count && existing.completed == completed) return
        val now = clock.millis().coerceAtLeast(0L)
        dao.upsertCheckIns(listOf(HabitCheckInEntity(
            habitId = id,
            localDateIso = date.toString(),
            completed = completed,
            completedAtUtcMs = if (completed) existing?.completedAtUtcMs ?: now else null,
            updatedAtUtcMs = HabitPolicy.nextUpdatedAt(now, existing?.updatedAtUtcMs),
            completedCount = count,
        )))
    }

    private suspend fun requireHabit(id: String): HabitEntity =
        dao.get(id) ?: throw IllegalArgumentException("Habit not found")
}
