package com.hippo.ehviewer.coil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartBackgroundInterceptorTest {
    @Test
    fun whitePageWithDarkBorderLinesSnapsToPureWhite() {
        val color = extractSmartBackgroundColor(width = 400, height = 600) { x, y ->
            // 外圈少量黑色装订线与局部黑框，其余大部分边距为近白纸面
            if (x == 0 || y == 0 || (x < 60 && y in 12..16)) {
                0xFF101010.toInt()
            } else {
                0xFFFDFDFD.toInt()
            }
        }
        assertEquals(0xFFFFFFFF.toInt(), color)
    }

    @Test
    fun blackPageWithWhiteLineSnapsToPureBlack() {
        val color = extractSmartBackgroundColor(width = 400, height = 600) { x, y ->
            if (x in 180..220 && y <= 8) {
                0xFFF5F5F5.toInt()
            } else {
                0xFF050505.toInt()
            }
        }
        assertEquals(0xFF000000.toInt(), color)
    }

    @Test
    fun agedYellowPaperPreservesWarmTone() {
        val paper = 0xFFF4EEDB.toInt()
        val color = extractSmartBackgroundColor(width = 300, height = 450) { _, _ -> paper }
        assertEquals(paper, color)
    }

    @Test
    fun croppedRectSamplesInnerVisibleEdgesOnly() {
        val outerWhite = 0xFFFFFFFF.toInt()
        val innerCream = 0xFFEDE2C8.toInt()
        val color = extractSmartBackgroundColor(
            width = 400,
            height = 600,
            left = 40,
            top = 50,
            right = 360,
            bottom = 550,
        ) { x, y ->
            if (x < 40 || x >= 360 || y < 50 || y >= 550) outerWhite else innerCream
        }
        assertEquals(innerCream, color)
    }

    @Test
    fun noisyVibrantEdgesTriggerDesaturationAndSoftening() {
        val palette = intArrayOf(
            0xFFFF0033.toInt(),
            0xFFFFAA00.toInt(),
            0xFF00DD44.toInt(),
            0xFF0099FF.toInt(),
            0xFFCC00FF.toInt(),
            0xFFFF0099.toInt(),
        )
        var counter = 0
        val color = extractSmartBackgroundColor(width = 500, height = 700) { x, y ->
            val idx = ((x / 19) + (y / 23) + counter++) % palette.size
            palette[idx]
        }
        assertNotNull(color)
        val c = color!!
        val r = (c ushr 16) and 0xFF
        val g = (c ushr 8) and 0xFF
        val b = c and 0xFF
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val saturation = if (maxC > 0) (maxC - minC) / maxC.toFloat() else 0f
        assertTrue("期望高饱和杂边被柔化降饱和，实际饱和度=$saturation", saturation < 0.55f)
    }

    @Test
    fun fullyTransparentEdgesReturnNull() {
        val color = extractSmartBackgroundColor(width = 300, height = 400) { _, _ -> 0x00FFFFFF }
        assertNull(color)
    }

    @Test
    fun invalidAndBoundaryDimensionsDoNotThrow() {
        assertNull(extractSmartBackgroundColor(width = 0, height = 0) { _, _ -> 0xFFFFFFFF.toInt() })
        assertNull(extractSmartBackgroundColor(width = -10, height = 100) { _, _ -> 0xFFFFFFFF.toInt() })
        assertNull(
            extractSmartBackgroundColor(
                width = 100,
                height = 100,
                left = 80,
                top = 80,
                right = 20,
                bottom = 20,
            ) { _, _ -> 0xFFFFFFFF.toInt() },
        )
        assertEquals(
            0xFF808080.toInt(),
            extractSmartBackgroundColor(width = 1, height = 1) { _, _ -> 0xFF808080.toInt() },
        )
        assertEquals(
            0xFF334455.toInt(),
            extractSmartBackgroundColor(
                width = 120,
                height = 120,
                left = -50,
                top = -50,
                right = 500,
                bottom = 500,
            ) { _, _ -> 0xFF334455.toInt() },
        )
    }

    @Test
    fun applyReaderColorFiltersTransformsCorrectly() {
        val warmColor = 0xFFCC6633.toInt()

        val inverted = applyReaderColorFilters(warmColor, grayScale = false, invertedColors = true)
        assertEquals(0xFF3399CC.toInt(), inverted)

        val gray = applyReaderColorFilters(warmColor, grayScale = true, invertedColors = false)
        val gr = (gray ushr 16) and 0xFF
        val gg = (gray ushr 8) and 0xFF
        val gb = gray and 0xFF
        assertEquals(gr, gg)
        assertEquals(gg, gb)

        val grayInverted = applyReaderColorFilters(warmColor, grayScale = true, invertedColors = true)
        val gir = (grayInverted ushr 16) and 0xFF
        val gig = (grayInverted ushr 8) and 0xFF
        val gib = grayInverted and 0xFF
        assertEquals(255 - gr, gir)
        assertEquals(gir, gig)
        assertEquals(gig, gib)
    }
}
