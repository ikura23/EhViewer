package com.hippo.ehviewer.ui.reader

import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class AnimatedPlaybackSnapTest {
    @Test
    fun `single page snap provider zeroes approach offset and delegates snap offset`() {
        val fakeDelegate = object : SnapLayoutInfoProvider {
            override fun calculateApproachOffset(velocity: Float, decayOffset: Float): Float = 999f

            override fun calculateSnapOffset(velocity: Float): Float = if (velocity > 0f) -320f else 180f
        }
        val provider = SinglePageSnapLayoutInfoProvider(fakeDelegate)

        assertEquals(0f, provider.calculateApproachOffset(velocity = 4000f, decayOffset = 1500f), 0f)
        assertEquals(0f, provider.calculateApproachOffset(velocity = -4000f, decayOffset = -1500f), 0f)
        assertEquals(-320f, provider.calculateSnapOffset(velocity = 500f), 0f)
        assertEquals(180f, provider.calculateSnapOffset(velocity = -500f), 0f)
    }

    @Test
    fun `resolveDominantVisiblePageIndex picks item with largest visible area`() {
        assertEquals(0, resolveDominantVisiblePageIndex(listOf(0 to 1080), fallbackIndex = 3))
        assertEquals(5, resolveDominantVisiblePageIndex(listOf(4 to 120, 5 to 960), fallbackIndex = 4))
        assertEquals(4, resolveDominantVisiblePageIndex(listOf(4 to 541, 5 to 539), fallbackIndex = 5))
        assertEquals(7, resolveDominantVisiblePageIndex(emptyList(), fallbackIndex = 7))
    }
}
