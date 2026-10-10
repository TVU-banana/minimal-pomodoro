package com.akira.clock.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Writes are coordinated by HabitRepository or the backup service's encompassing transaction. */
@Dao
internal interface HabitDao {
    @Query("SELECT * FROM habits ORDER BY sortOrder, createdAtUtcMs, id")
    fun observeHabits(): Flow<List<HabitEntity>>

    @Query(
        "SELECT * FROM habit_check_ins WHERE localDateIso >= :start AND localDateIso <= :end " +
            "ORDER BY localDateIso, habitId",
    )
    fun observeCheckIns(start: String, end: String): Flow<List<HabitCheckInEntity>>

    @Query("SELECT * FROM habits ORDER BY sortOrder, createdAtUtcMs, id")
    suspend fun getAll(): List<HabitEntity>

    @Query("SELECT * FROM habit_check_ins ORDER BY localDateIso, habitId")
    suspend fun getAllCheckIns(): List<HabitCheckInEntity>

    @Query("SELECT * FROM habits WHERE id = :id LIMIT 1")
    suspend fun get(id: String): HabitEntity?

    @Query(
        "SELECT * FROM habit_check_ins " +
            "WHERE habitId = :habitId AND localDateIso = :date LIMIT 1",
    )
    suspend fun getCheckIn(habitId: String, date: String): HabitCheckInEntity?

    @Query("SELECT * FROM habit_targets ORDER BY habitId, effectiveLocalDateIso")
    fun observeTargets(): Flow<List<HabitTargetEntity>>

    @Query("SELECT * FROM habit_targets ORDER BY habitId, effectiveLocalDateIso")
    suspend fun getAllTargets(): List<HabitTargetEntity>

    @Upsert
    suspend fun upsertTargets(targets: List<HabitTargetEntity>)

    @Upsert
    suspend fun upsertHabits(habits: List<HabitEntity>)

    @Upsert
    suspend fun upsertCheckIns(checkIns: List<HabitCheckInEntity>)
}
