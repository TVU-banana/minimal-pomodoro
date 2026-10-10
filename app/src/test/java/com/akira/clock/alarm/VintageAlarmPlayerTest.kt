package com.akira.clock.alarm

import org.junit.Assert.assertEquals
import org.junit.Test

class VintageAlarmPlayerTest {
    @Test
    fun completionPatternHasTwoThreeBeepBursts() {
        assertEquals(
            listOf(0L, 180L, 360L, 800L, 980L, 1_160L),
            VintageAlarmPlayer.beepStartOffsetsMs(),
        )
    }
}
