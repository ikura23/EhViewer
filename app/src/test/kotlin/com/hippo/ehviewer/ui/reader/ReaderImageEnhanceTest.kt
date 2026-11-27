package com.hippo.ehviewer.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderImageEnhanceTest {

    @Test
    fun `fromPrefValue maps valid values and falls back to OFF on unknown value`() {
        assertEquals(ReaderImageEnhanceMode.OFF, ReaderImageEnhanceMode.fromPrefValue(0))
        assertEquals(ReaderImageEnhanceMode.SHARPEN_CAS, ReaderImageEnhanceMode.fromPrefValue(1))
        assertEquals(ReaderImageEnhanceMode.ANIME_ENHANCE, ReaderImageEnhanceMode.fromPrefValue(2))
        assertEquals(ReaderImageEnhanceMode.OFF, ReaderImageEnhanceMode.fromPrefValue(-1))
        assertEquals(ReaderImageEnhanceMode.OFF, ReaderImageEnhanceMode.fromPrefValue(99))
    }

    @Test
    fun `isGpuImageEnhanceSupported requires Android 13 or higher`() {
        assertFalse(isGpuImageEnhanceSupported(32))
        assertTrue(isGpuImageEnhanceSupported(33))
        assertTrue(isGpuImageEnhanceSupported(35))
    }

    @Test
    fun `normalizeEnhanceStrength clamps percentage into valid range`() {
        assertEquals(0.10f, normalizeEnhanceStrength(0), 0.0001f)
        assertEquals(0.65f, normalizeEnhanceStrength(65), 0.0001f)
        assertEquals(1.00f, normalizeEnhanceStrength(150), 0.0001f)
    }

    @Test
    fun `computeEnhanceTexelStep handles upscaling clamping downscaling and invalid input`() {
        // 低清 540x720 放大到 1080x1440 -> 步长 2.0
        assertEquals(2.0f, computeEnhanceTexelStep(1080f, 1440f, 540f, 720f), 0.0001f)
        // 超低清图放大倍数极大时上限夹紧在 3.0
        assertEquals(3.0f, computeEnhanceTexelStep(1080f, 1920f, 180f, 320f), 0.0001f)
        // 4K 高清图缩小展示保持 1.0
        assertEquals(1.0f, computeEnhanceTexelStep(1080f, 1440f, 2160f, 2880f), 0.0001f)
        // 接近原尺寸（<= 1.05x）保持 1.0
        assertEquals(1.0f, computeEnhanceTexelStep(1030f, 1030f, 1000f, 1000f), 0.0001f)
        // 0 或非有限尺寸安全回退 1.0
        assertEquals(1.0f, computeEnhanceTexelStep(0f, 1000f, 500f, 500f), 0.0001f)
        assertEquals(1.0f, computeEnhanceTexelStep(1000f, 1000f, Float.NaN, 500f), 0.0001f)
    }

    @Test
    fun `agsl shaders declare required uniforms and valid entry points`() {
        listOf(CAS_SHARPEN_AGSL, ANIME_ENHANCE_AGSL).forEach { shader ->
            assertTrue(shader.contains("uniform shader content;"))
            assertTrue(shader.contains("uniform float strength;"))
            assertTrue(shader.contains("uniform float texelStep;"))
            assertTrue(shader.contains("half4 main(float2 fragCoord)"))
            assertFalse(shader.contains("void main"))
            assertFalse(shader.contains("texture2D"))
        }
    }
}
