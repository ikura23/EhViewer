package com.hippo.ehviewer.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderEnhancementsTest {
    @Test
    fun `packArgb encodes channels in standard ARGB order`() {
        assertEquals(0x34FFD68C.toInt(), packArgb(a = 52, r = 255, g = 214, b = 140))
        assertEquals(0x5A201C18.toInt(), packArgb(a = 90, r = 32, g = 28, b = 24))
    }

    @Test
    fun `detectActiveColorFilterPreset matches enabled presets only`() {
        val warm = ReaderColorFilterPreset.WARM_EYE_CARE
        val soft = ReaderColorFilterPreset.SOFT_DIM

        assertEquals(warm, detectActiveColorFilterPreset(enabled = true, argb = warm.argb, mode = 0))
        assertEquals(soft, detectActiveColorFilterPreset(enabled = true, argb = soft.argb, mode = 0))
        assertNull(detectActiveColorFilterPreset(enabled = false, argb = warm.argb, mode = 0))
        assertNull(detectActiveColorFilterPreset(enabled = true, argb = warm.argb, mode = 1))
        assertNull(detectActiveColorFilterPreset(enabled = true, argb = 0x12345678, mode = 0))
    }

    @Test
    fun `calculatePreviewInSampleSize keeps power of two sampling`() {
        assertEquals(1, calculatePreviewInSampleSize(width = 200, height = 300, maxDim = 256))
        assertEquals(4, calculatePreviewInSampleSize(width = 800, height = 1200, maxDim = 256))
        assertEquals(16, calculatePreviewInSampleSize(width = 2400, height = 4096, maxDim = 256))
        assertEquals(1, calculatePreviewInSampleSize(width = 0, height = 1000, maxDim = 256))
    }

    @Test
    fun `calculatePreviewBubbleOffsetFraction handles ltr rtl and edge bounds`() {
        assertEquals(0f, calculatePreviewBubbleOffsetFraction(pageOneBased = 1, totalPages = 11, isRtl = false), 0.0001f)
        assertEquals(0.5f, calculatePreviewBubbleOffsetFraction(pageOneBased = 6, totalPages = 11, isRtl = false), 0.0001f)
        assertEquals(1f, calculatePreviewBubbleOffsetFraction(pageOneBased = 11, totalPages = 11, isRtl = false), 0.0001f)
        assertEquals(1f, calculatePreviewBubbleOffsetFraction(pageOneBased = 1, totalPages = 11, isRtl = true), 0.0001f)
        assertEquals(0f, calculatePreviewBubbleOffsetFraction(pageOneBased = 11, totalPages = 11, isRtl = true), 0.0001f)
        assertEquals(0.5f, calculatePreviewBubbleOffsetFraction(pageOneBased = 1, totalPages = 1, isRtl = false), 0.0001f)
        assertEquals(1f, calculatePreviewBubbleOffsetFraction(pageOneBased = 99, totalPages = 5, isRtl = false), 0.0001f)
    }
}
