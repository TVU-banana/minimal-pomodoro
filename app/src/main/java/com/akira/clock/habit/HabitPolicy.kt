package com.akira.clock.habit

import com.akira.clock.data.HabitCheckInEntity
import com.akira.clock.data.HabitEntity
import com.akira.clock.data.HabitTargetEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal enum class HabitDayState { COMPLETED, INCOMPLETE, NOT_APPLICABLE, FUTURE }

internal data class HabitSnapshot(
    val habits: List<HabitEntity>,
    val checkIns: List<HabitCheckInEntity>,
    val targets: List<HabitTargetEntity> = emptyList(),
)

/** Pure calendar rules; UTC completion timestamps never change a check-in's stored day. */
internal object HabitPolicy {
    const val MAX_NAME_LENGTH = 120
    const val MAX_NOTE_LENGTH = 1000
    const val MAX_TARGET_COUNT = 99

    val displayOrder: Comparator<HabitEntity> = compareBy(
        { it.sortOrder },
        { it.createdAtUtcMs },
        { it.id },
    )

    fun requireTarget(count: Int): Int = count.also {
        require(it in 1..MAX_TARGET_COUNT) { "Invalid habit target" }
    }

    /** Date-scoped targets also cover days with no check-in row; older backups default to one. */
    fun targetFor(id: String, date: LocalDate, targets: List<HabitTargetEntity>): Int =
        targets.filter { it.habitId == id && it.effectiveLocalDateIso <= date.toString() }
            .maxByOrNull { it.effectiveLocalDateIso }?.targetCount ?: 1

    fun countFor(id: String, date: LocalDate, checkIns: List<HabitCheckInEntity>): Int =
        checkIns.find { it.habitId == id && it.localDateIso == date.toString() }
            ?.completedCount ?: 0

    fun normalizeName(name: String): String = name.trim().also {
        require(it.isNotEmpty() && it.length <= MAX_NAME_LENGTH) { "Invalid habit name" }
    }

    fun normalizeNote(note: String): String = note.trim().also {
        require(it.length <= MAX_NOTE_LENGTH) { "Invalid habit note" }
    }

    fun localDateAt(utcMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(utcMillis).atZone(zone).toLocalDate()

    /** Creation and archive days remain eligible history, preserving archive-day completions. */
    fun isEligible(habit: HabitEntity, date: LocalDate): Boolean =
        date >= LocalDate.parse(habit.createdLocalDateIso) &&
            habit.archivedLocalDateIso?.let { date <= LocalDate.parse(it) } != false

    fun stateFor(
        habit: HabitEntity,
        date: LocalDate,
        today: LocalDate,
        checkIns: List<HabitCheckInEntity>,
        targets: List<HabitTargetEntity> = emptyList(),
    ): HabitDayState {
        if (!isEligible(habit, date)) return HabitDayState.NOT_APPLICABLE
        if (date > today) return HabitDayState.FUTURE
        val completed = countFor(habit.id, date, checkIns) >= targetFor(habit.id, date, targets)
        return if (completed) HabitDayState.COMPLETED else HabitDayState.INCOMPLETE
    }

    /** Rows exist only on eligible dates; archives appear only on eligible past days. */
    fun visibleHabits(
        snapshot: HabitSnapshot,
        date: LocalDate,
        today: LocalDate,
    ): List<HabitEntity> = snapshot.habits.filter {
        isEligible(it, date) && (it.archivedLocalDateIso == null || date < today)
    }.sortedWith(displayOrder)

    /** Clock collisions or backwards adjustments must not weaken backup conflict ordering. */
    fun nextUpdatedAt(nowUtcMs: Long, previousUtcMs: Long?): Long {
        require(previousUtcMs != Long.MAX_VALUE) { "Habit timestamp exhausted" }
        return maxOf(nowUtcMs.coerceAtLeast(0L), previousUtcMs?.plus(1) ?: 0L)
    }
}
