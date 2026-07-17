package com.akira.clock.timer

interface ClockSource {
    fun elapsedRealtimeMs(): Long

    fun utcNowMs(): Long
}
