package com.akira.clock.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TagColorsTest {
    @Test
    fun parsesCustomColorsWithOpaqueAlpha() {
        assertEquals(0xFF315BCBL, TagColors.parseHex(" #315bcb "))
        assertEquals(0xFFFFFFFFL, TagColors.parseHex("FFFFFF"))
        assertEquals(0xFF000000L, TagColors.parseHex("000000"))
    }

    @Test
    fun rejectsSignedAndMalformedColors() {
        listOf("-00001", "+00001", "#123", "#12345678", "#GGGGGG", "").forEach {
            assertNull(TagColors.parseHex(it))
        }
    }

    @Test
    fun roundTripsSavedColors() {
        listOf(TagColors.RED, TagColors.YELLOW, TagColors.GREEN, 0xFF315BCBL).forEach {
            assertEquals(it, TagColors.parseHex(TagColors.formatHex(it)))
        }
    }
}
