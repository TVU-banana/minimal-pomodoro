package com.akira.clock.ui

import com.akira.clock.data.ClockDatabase
import com.akira.clock.data.SessionEntity
import com.akira.clock.data.SessionStatus
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Immutable aggregated progress snapshot for a local calendar day.
 *
 * Encapsulates accumulated focus duration [creditedMs] against the configured goal [goalMs].
 */
internal data class TodayProgress(val creditedMs: Long, val goalMs: Long) {
    val fraction: Float
        get() = if (goalMs > 0) {
            (creditedMs.toDouble() / goalMs).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }
}

/** Mirrors the Today screen's completion credit, counting each session once across tag overlaps. */
internal fun todayProgress(
    sessions: List<SessionEntity>,
    activePresetIds: Set<String>,
    goalMs: Long,
    dayStartMs: Long,
    dayEndMs: Long,
): TodayProgress {
    val total = sessions.asSequence()
        .filter { it.presetId != null && it.presetId in activePresetIds }
        .filter { it.startedAtUtcMs in dayStartMs until dayEndMs }
        .sumOf { session ->
            when (session.status) {
                SessionStatus.COMPLETED.name, SessionStatus.ABORTED.name -> session.actualFocusMs
                else -> 0L
            }
        }
    return TodayProgress(total, goalMs.coerceAtLeast(0L))
}

/** Reads only on Room invalidations or collection restart; UI ticks never query persistence. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class TodayProgressLoader(
    private val database: ClockDatabase,
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
    private val clockProvider: () -> Long = { System.currentTimeMillis() },
) {
    fun observe(): Flow<TodayProgress> = todayRangeFlow().flatMapLatest { (dayStartMs, dayEndMs) ->
        combine(
            database.categoryDao().observeActive(),
            database.sessionDao().observeInRange(dayStartMs, dayEndMs),
            database.taskPresetTagDao().observeAll(),
        ) { tags, sessions, links ->
            val activeTagIds = tags.mapTo(hashSetOf()) { it.id }
            val activePresetIds = links.filter { it.categoryId in activeTagIds }
                .mapTo(hashSetOf()) { it.presetId }
            todayProgress(
                sessions = sessions,
                activePresetIds = activePresetIds,
                goalMs = tags.sumOf { it.dailyGoalMs.coerceAtLeast(0L) },
                dayStartMs = dayStartMs,
                dayEndMs = dayEndMs,
            )
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    /** Emits each active tag's own goal progress for the timer cards. */
    fun observeByTag(): Flow<Map<String, TodayProgress>> =
        todayRangeFlow().flatMapLatest { (dayStartMs, dayEndMs) ->
            combine(
                database.categoryDao().observeActive(),
                database.sessionDao().observeInRange(dayStartMs, dayEndMs),
                database.taskPresetTagDao().observeAll(),
            ) { tags, sessions, links ->
                val presetIdsByTag = links.groupBy { it.categoryId }
                    .mapValues { (_, tagLinks) -> tagLinks.mapTo(hashSetOf()) { it.presetId } }
                tags.associate { tag ->
                    tag.id to todayProgress(
                        sessions = sessions,
                        activePresetIds = presetIdsByTag[tag.id].orEmpty(),
                        goalMs = tag.dailyGoalMs,
                        dayStartMs = dayStartMs,
                        dayEndMs = dayEndMs,
                    )
                }
            }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    private fun todayRangeFlow(): Flow<Pair<Long, Long>> = flow {
        while (true) {
            val nowMs = clockProvider()
            val zone = zoneProvider()
            val today = LocalDate.now(zone)
            val dayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEndMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            emit(dayStartMs to dayEndMs)
            val delayMs = (dayEndMs - nowMs).coerceAtLeast(1_000L)
            delay(delayMs)
        }
    }.distinctUntilChanged()
}
