package com.akira.clock.timer

class AndroidClockSource : ClockSource {
    override fun elapsedRealtimeMs(): Long = android.os.SystemClock.elapsedRealtime()

    override fun utcNowMs(): Long = System.currentTimeMillis()
}
