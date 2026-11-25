package com.hippo.ehviewer.coil

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.hardware.HardwareBuffer
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import com.ehviewer.core.util.isAtLeastQ
import com.ehviewer.core.util.logcat
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val USAGE = HardwareBuffer.USAGE_CPU_WRITE_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE

private class Frame(val bitmap: Bitmap, val ahb: HardwareBuffer?, var timestamp: Int) : AutoCloseable {
    override fun close() {
        bitmap.recycle()
        ahb?.close()
    }

    fun decode(decoder: Long, reset: Boolean = false): Int {
        timestamp = if (ahb == null) {
            nativeDecodeNextFrame(decoder, reset, bitmap)
        } else {
            nativeDecodeNextFrameZeroCopy(decoder, reset, ahb)
        }
        return timestamp
    }
}

private fun allocFrame(w: Int, h: Int): Frame {
    return if (isAtLeastQ) {
        val ahb = HardwareBuffer.create(w, h, HardwareBuffer.RGBA_8888, 1, USAGE)
        val bitmap = Bitmap.wrapHardwareBuffer(ahb, ColorSpace.get(ColorSpace.Named.SRGB))
        if (bitmap == null) {
            ahb.close()
            return Frame(createBitmap(w, h), null, 0)
        }
        Frame(bitmap, ahb, 0)
    } else {
        Frame(createBitmap(w, h), null, 0)
    }
}

// Hold a reference to the buffer as it's used by the decoder
@Suppress("CanBeParameter")
class AnimatedWebPDrawable(private val source: ByteBuffer) : Drawable(), Animatable, AutoCloseable {
    private val decodeScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1))
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val decoder = nativeCreateDecoder(source)
    private val width: Int
    private val height: Int
    private val loopCount: Int

    private var decodeJob: Job? = null
    private var loopsCompleted = 0
    private var timeToShowNextFrame = 0L
    private var endNotified = false
    private val disposed = AtomicBoolean(false)

    var playOnce = false
    var loopForever = false
    var speed = 1f
        set(value) {
            val updated = if (value.isFinite()) value.coerceIn(0.1f, 5f) else 1f
            val previous = field
            if (updated == previous) return
            val now = SystemClock.uptimeMillis()
            val remaining = (timeToShowNextFrame - now).coerceAtLeast(0)
            timeToShowNextFrame = now + (remaining * previous / updated).toLong()
            field = updated
            unscheduleSelf(runnable)
            if (decodeJob?.isCompleted == true) scheduleSelf(runnable, timeToShowNextFrame)
        }
    var onAnimationEnd: (() -> Unit)? = null
    private var currentFrame: Frame
    private var nextFrame: Frame

    init {
        check(decoder != 0L) { "Failed to create decoder" }
        val packed = nativeGetImageInfo(decoder)
        width = (packed shr 40).toInt()
        height = (packed shr 16 and 0xFFFFFF).toInt()
        loopCount = (packed and 0xFFFF).toInt()
        currentFrame = allocFrame(width, height)
        val timestamp = currentFrame.decode(decoder)
        check(timestamp != 0) {
            nativeDestroyDecoder(decoder)
            "Failed to decode first frame"
        }
        nextFrame = allocFrame(width, height)
    }

    override fun getIntrinsicWidth() = width

    override fun getIntrinsicHeight() = height

    private val runnable = Runnable {
        invalidateSelf()
    }

    private fun scheduleNextFrame() {
        val frameDuration = (nextFrame.timestamp - currentFrame.timestamp).coerceAtLeast(0)
        timeToShowNextFrame = SystemClock.uptimeMillis() +
            (frameDuration / speed.coerceAtLeast(0.05f)).toLong()
        scheduleSelf(runnable, timeToShowNextFrame)
    }

    private fun decodeNextFrame(reset: Boolean) = decodeScope.launch {
        if (disposed.get()) return@launch
        val timestamp = nextFrame.decode(decoder, reset)
        ensureActive()
        check(timestamp != 0) {
            decodeJob = null
            "Failed to decode next frame"
        }
        if (timestamp <= currentFrame.timestamp) {
            if (reset) loopsCompleted = 0 else loopsCompleted++
            currentFrame.timestamp = 0
        }
        nextFrame.bitmap.prepareToDraw()
    }.apply {
        invokeOnCompletion { cause ->
            if (disposed.get()) return@invokeOnCompletion
            when (cause) {
                null -> if (reset) {
                    timeToShowNextFrame = SystemClock.uptimeMillis()
                    runnable.run()
                } else {
                    scheduleNextFrame()
                }
                !is CancellationException -> logcat(cause)
            }
        }
    }

    override fun draw(canvas: Canvas) {
        if (disposed.get()) return
        if (decodeJob?.isCompleted == true && isVisible && SystemClock.uptimeMillis() >= timeToShowNextFrame) {
            val maxLoops = when {
                playOnce -> 1
                loopForever -> 0
                else -> loopCount
            }
            decodeJob = if (maxLoops == 0 || loopsCompleted < maxLoops) {
                currentFrame = nextFrame.also { nextFrame = currentFrame }
                decodeNextFrame(false)
            } else {
                notifyAnimationEnd()
                null
            }
        }
        canvas.drawBitmap(currentFrame.bitmap, null, bounds, paint)
    }

    private fun notifyAnimationEnd() {
        if (!endNotified) {
            endNotified = true
            onAnimationEnd?.invoke()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setAlpha(alpha: Int) {
        if (alpha != paint.alpha) {
            paint.alpha = alpha
            invalidateSelf()
        }
    }

    override fun getAlpha(): Int = paint.alpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        if (colorFilter != paint.colorFilter) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }
    }

    override fun getColorFilter(): ColorFilter? = paint.colorFilter

    override fun isRunning() = decodeJob != null

    override fun start() {
        if (!disposed.get() && decodeJob == null) {
            endNotified = false
            loopsCompleted = 0
            decodeJob = decodeNextFrame(true)
        }
    }

    /** 重置循环计数与结束标志并从首帧重新起播，避免预组合后台播完导致秒跳页。 */
    fun restart() {
        stop()
        start()
    }

    override fun stop() {
        decodeJob?.cancel()
        decodeJob = null
        unscheduleSelf(runnable)
    }

    override fun close() {
        if (!disposed.compareAndSet(false, true)) return
        stop()
        runBlocking { decodeScope.coroutineContext.job.cancelAndJoin() }
        nativeDestroyDecoder(decoder)
        currentFrame.close()
        nextFrame.close()
    }
}

private external fun nativeCreateDecoder(source: ByteBuffer): Long
private external fun nativeGetImageInfo(decoder: Long): Long
private external fun nativeDecodeNextFrame(decoder: Long, reset: Boolean, bitmap: Bitmap): Int
private external fun nativeDecodeNextFrameZeroCopy(decoder: Long, reset: Boolean, ahb: HardwareBuffer): Int
private external fun nativeDestroyDecoder(decoder: Long)
