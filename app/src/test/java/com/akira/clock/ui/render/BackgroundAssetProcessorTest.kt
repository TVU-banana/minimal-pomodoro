package com.akira.clock.ui.render

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundAssetProcessorTest {
    @Test
    fun `sampling uses powers of two without decoding below target`() {
        assertEquals(4, BackgroundAssetProcessor.calculateInSampleSize(4_000, 3_000, 1_000, 750))
        assertEquals(2, BackgroundAssetProcessor.calculateInSampleSize(4_000, 3_000, 1_200, 900))
        assertEquals(1, BackgroundAssetProcessor.calculateInSampleSize(800, 600, 1_000, 1_000))
    }

    @Test
    fun `target size preserves aspect ratio and never upscales`() {
        assertEquals(1_200 to 900, BackgroundAssetProcessor.calculateTargetSize(4_000, 3_000, 1_200, 1_000))
        assertEquals(800 to 600, BackgroundAssetProcessor.calculateTargetSize(800, 600, 1_200, 1_000))
        assertEquals(563 to 1_000, BackgroundAssetProcessor.calculateTargetSize(1_080, 1_920, 1_200, 1_000))
    }
}
