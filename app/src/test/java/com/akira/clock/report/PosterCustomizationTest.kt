package com.akira.clock.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterCustomizationTest {
    @Test
    fun sectionsDefaultToShowingEveryOptionalModule() {
        assertEquals(PosterSections(), PosterSections(true, true, true))
    }

    @Test
    fun reflectionValidationUsesUnicodeCodePoints() {
        assertTrue(DailyReflectionText.isValid("😀".repeat(120)))
        assertFalse(DailyReflectionText.isValid("😀".repeat(121)))
        assertFalse(DailyReflectionText.isValid("  \n "))
    }

    @Test
    fun layoutOmitsHiddenCardsAndKeepsVisibleCardsSeparate() {
        listOf(false, true).forEach { chart ->
            listOf(false, true).forEach { reflection ->
                listOf(false, true).forEach { motivation ->
                    val sections = PosterSections(chart, reflection, motivation)
                    val layout = PosterLayoutPlanner.plan(sections)
                    if (chart) assertNotNull(layout.chart) else assertNull(layout.chart)
                    if (reflection) {
                        assertNotNull(layout.reflection)
                    } else {
                        assertNull(layout.reflection)
                    }
                    if (motivation) {
                        assertNotNull(layout.motivationCenterY)
                    } else {
                        assertNull(layout.motivationCenterY)
                    }
                    val chartBounds = layout.chart
                    val reflectionBounds = layout.reflection
                    if (chartBounds != null && reflectionBounds != null) {
                        assertTrue(chartBounds.bottom < reflectionBounds.top)
                    }
                }
            }
        }
    }
    @Test
    fun disablingAnyModuleStrictlyShrinksEveryApplicableCombination() {
        for (mask in 1..7) {
            fun sections(bits: Int) = PosterSections(
                bits and 1 != 0, bits and 2 != 0, bits and 4 != 0,
            )
            val before = PosterLayoutPlanner.plan(sections(mask))
            for (bit in listOf(1, 2, 4)) {
                if (mask and bit == 0) continue
                val after = PosterLayoutPlanner.plan(sections(mask and bit.inv()))
                assertTrue("mask=$mask bit=$bit", after.height < before.height)
            }
        }
    }

    @Test
    fun visibleModulesMoveUpWithConsistentGapsAndBottomPadding() {
        for (mask in 0..7) {
            val layout = PosterLayoutPlanner.plan(PosterSections(
                mask and 1 != 0, mask and 2 != 0, mask and 4 != 0,
            ))
            val cards = listOfNotNull(layout.chart, layout.reflection)
            cards.firstOrNull()?.let { assertEquals(520f, it.top, 0f) }
            cards.zipWithNext().forEach { (first, second) ->
                assertEquals(32f, second.top - first.bottom, 0f)
            }
            val lastCardBottom = cards.lastOrNull()?.bottom
            val motivation = layout.motivationCenterY
            if (motivation != null) {
                val top = motivation - 32f
                assertEquals(lastCardBottom?.plus(32f) ?: 520f, top, 0f)
                assertEquals(112f, layout.height - (motivation + 32f), 0f)
            } else {
                assertEquals(112f, layout.height - (lastCardBottom ?: 456f), 0f)
            }
        }
    }

}
