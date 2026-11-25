package com.hippo.ehviewer.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedPlaybackSpeedTest {
    @Test
    fun formatPlaybackSpeedShowsPrefixXAndTrimmedZeros() {
        assertEquals("x0.25", formatPlaybackSpeed(0.25f))
        assertEquals("x0.5", formatPlaybackSpeed(0.5f))
        assertEquals("x0.75", formatPlaybackSpeed(0.75f))
        assertEquals("x1", formatPlaybackSpeed(1f))
        assertEquals("x1.25", formatPlaybackSpeed(1.25f))
        assertEquals("x1.5", formatPlaybackSpeed(1.5f))
        assertEquals("x2", formatPlaybackSpeed(2f))
        assertEquals("x4", formatPlaybackSpeed(4f))
        assertEquals("0.5", formatPlaybackSpeedNumber(0.5f))
        assertEquals("2", formatPlaybackSpeedNumber(2f))
    }

    @Test
    fun sanitizePlaybackSpeedInputStripsXAndInvalidCharacters() {
        assertEquals("2", sanitizePlaybackSpeedInput("x2"))
        assertEquals("1.75", sanitizePlaybackSpeedInput("X 1.75x"))
        assertEquals("0.5", sanitizePlaybackSpeedInput(".5"))
        assertEquals("1.25", sanitizePlaybackSpeedInput("1..2.5abc"))
        assertEquals("", sanitizePlaybackSpeedInput("xX-abc"))
    }

    @Test
    fun parseCustomPlaybackSpeedValidatesRange() {
        assertEquals(0.1f, parseCustomPlaybackSpeed("0.1"))
        assertEquals(1.8f, parseCustomPlaybackSpeed("x1.8"))
        assertEquals(10f, parseCustomPlaybackSpeed("10"))
        assertNull(parseCustomPlaybackSpeed("0.05"))
        assertNull(parseCustomPlaybackSpeed("10.5"))
        assertNull(parseCustomPlaybackSpeed(""))
        assertNull(parseCustomPlaybackSpeed("."))
        assertTrue(PLAYBACK_SPEED_PRESETS.containsAll(listOf(0.5f, 1f, 2f)))
    }
}
