package com.akira.clock.timer

/** Production clock backed by Android monotonic time and UTC wall-clock time. */
internal class AndroidClockSource : ClockSource {
    override fun elapsedRealtimeMs(): Long = android.os.SystemClock.elapsedRealtime()

    override fun utcNowMs(): Long = System.currentTimeMillis()
}
