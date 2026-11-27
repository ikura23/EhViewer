package com.hippo.ehviewer.coil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedWebPPlaybackClockTest {

    @Test
    fun `computeWebPDecodeDimensions preserves original when no constraint or close to original`() {
        assertEquals(1200 to 1600, computeWebPDecodeDimensions(1200, 1600, null, null))
        assertEquals(1200 to 1600, computeWebPDecodeDimensions(1200, 1600, 2000, 3000))
        assertEquals(1000 to 1000, computeWebPDecodeDimensions(1000, 1000, 900, 900))
    }

    @Test
    fun `computeWebPDecodeDimensions downscales proportionally for thumbnails`() {
        val (dw, dh) = computeWebPDecodeDimensions(1200, 1600, 300, 400)
        assertEquals(300 to 400, dw to dh)

        // 给定单一宽度目标时按比例缩小
        val (w2, h2) = computeWebPDecodeDimensions(1000, 2000, 250, null)
        assertEquals(250 to 500, w2 to h2)

        // 不允许缩放到极小失真尺寸
        assertEquals(20 to 20, computeWebPDecodeDimensions(20, 20, 4, 4))
    }

    @Test
    fun `computeMaxSkipFrames disables skipping near 1x and scales up safely`() {
        assertEquals(0, computeMaxSkipFrames(1.0f))
        assertEquals(0, computeMaxSkipFrames(1.25f))
        assertTrue(computeMaxSkipFrames(2.0f) in 2..8)
        assertTrue(computeMaxSkipFrames(4.0f) in 4..8)
        assertEquals(8, computeMaxSkipFrames(20.0f))
    }

    @Test
    fun `unpackFrameTimestamp and unpackFrameWrapped decode 64-bit native result`() {
        val normal = 240L
        assertEquals(240, unpackFrameTimestamp(normal))
        assertFalse(unpackFrameWrapped(normal))

        val wrapped = (1L shl 32) or 40L
        assertEquals(40, unpackFrameTimestamp(wrapped))
        assertTrue(unpackFrameWrapped(wrapped))
    }

    @Test
    fun `clock advances accurately across speeds and wraps smoothly`() {
        val clock = AnimatedWebPClock(initialSpeed = 2.0f)
        val t0 = 1_000_000_000L
        assertEquals(0, clock.advanceTo(t0))

        // 16ms 壁钟在 2.0x 下推进 32ms
        assertEquals(32, clock.advanceTo(t0 + 16_000_000L))

        // 途中切换到 3.0x，再过 20ms 应精确累加 60ms -> 92ms
        clock.setSpeed(3.0f, t0 + 16_000_000L)
        assertEquals(92, clock.advanceTo(t0 + 36_000_000L))

        // 超过 100ms 的单步壁钟异常延迟会被夹紧在 100ms * 3.0x = 300ms
        assertEquals(392, clock.advanceTo(t0 + 2_036_000_000L))

        // 片尾周期为 350ms，回绕后余数 42ms 无缝延续
        clock.onWrapped(350)
        assertEquals(350, clock.loopDurationMs)
        assertEquals(42, clock.currentMediaTimeMs())
    }

    @Test
    fun `clock pause preserves media position without jump on resume`() {
        val clock = AnimatedWebPClock(initialSpeed = 1.5f)
        val t0 = 5_000_000_000L
        assertEquals(0, clock.advanceTo(t0))
        assertEquals(30, clock.advanceTo(t0 + 20_000_000L))

        clock.pause()
        // 暂停期间壁钟过去 5 秒，恢复首帧应停在 30ms 不突变
        val tResume = t0 + 5_020_000_000L
        assertEquals(30, clock.advanceTo(tResume))
        // 再过 20ms 推进 30ms -> 60ms
        assertEquals(60, clock.advanceTo(tResume + 20_000_000L))
    }
}
