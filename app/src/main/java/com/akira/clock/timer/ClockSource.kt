package com.akira.clock.timer

/** Monotonic and wall-clock time required by timer transitions. */
internal interface ClockSource {
    fun elapsedRealtimeMs(): Long

    fun utcNowMs(): Long
}
