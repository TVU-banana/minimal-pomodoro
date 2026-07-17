package com.akira.clock.alarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmIdempotencyTest {
    @Test
    fun oldPhaseAlarmCannotAdvanceNewerPhase() {
        assertFalse(
            deadlineIsEligible(
                receivedDeadlineUtcMs = 1_000L,
                storedDeadlineUtcMs = 2_000L,
            ),
        )
    }

    @Test
    fun matchingAlarmIsEligibleAfterWallClockChange() {
        assertTrue(
            deadlineIsEligible(
                receivedDeadlineUtcMs = 2_000L,
                storedDeadlineUtcMs = 2_000L,
            ),
        )
    }

    @Test
    fun laterAlarmMayCatchUpOverduePhase() {
        assertTrue(
            deadlineIsEligible(
                receivedDeadlineUtcMs = 3_000L,
                storedDeadlineUtcMs = 2_000L,
            ),
        )
    }
}
