package com.akira.clock.alarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmIdempotencyTest {
    @Test
    fun `old phase alarm cannot advance a newer phase`() {
        assertFalse(
            deadlineIsEligible(
                receivedDeadlineUtcMs = 1_000L,
                storedDeadlineUtcMs = 2_000L,
            ),
        )
    }

    @Test
    fun `matching alarm is eligible even if wall clock changed`() {
        assertTrue(
            deadlineIsEligible(
                receivedDeadlineUtcMs = 2_000L,
                storedDeadlineUtcMs = 2_000L,
            ),
        )
    }

    @Test
    fun `later alarm may catch up an overdue phase after interrupted persistence`() {
        assertTrue(
            deadlineIsEligible(
                receivedDeadlineUtcMs = 3_000L,
                storedDeadlineUtcMs = 2_000L,
            ),
        )
    }
}
