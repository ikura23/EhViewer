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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val USAGE = HardwareBuffer.USAGE_CPU_WRITE_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE

/** 正常全尺寸播放时使用的环形缓冲帧数（1 帧正在绘制 + 2 帧预解码）。 */
private const val FULL_SIZE_BUFFER_COUNT = 3

/** 缩略图降采样播放时使用的环形缓冲帧数（1 帧正在绘制 + 1 帧预解码）。 */
private const val DOWNSCALED_BUFFER_COUNT = 2

/** 触发降采样的最小缩放阈值，避免接近原尺寸时丧失 1:1 整块 memcpy 快速路径。 */
private const val DOWNSCALE_THRESHOLD = 0.85

/** 降采样后的最小边长（像素），避免缩略图过度模糊。 */
private const val MIN_DOWNSCALED_DIMENSION = 16

/** 单帧推进时允许的最大单步壁钟间隔（纳秒），防止切后台恢复时单帧巨幅跳变。 */
private const val MAX_FRAME_STEP_NS = 100_000_000L

/** 高倍速追帧时单次允许跳过的最大中间帧数。 */
private const val MAX_SKIP_FRAMES_CAP = 8

/**
 * 计算 WebP 动图实际解码缓冲尺寸；未提供有效目标尺寸或接近原尺寸时保持原图尺寸，绝不放大。
 */
internal fun computeWebPDecodeDimensions(
    srcWidth: Int,
    srcHeight: Int,
    targetWidth: Int?,
    targetHeight: Int?,
): Pair<Int, Int> {
    if (srcWidth <= 0 || srcHeight <= 0) {
        return srcWidth.coerceAtLeast(1) to srcHeight.coerceAtLeast(1)
    }
    val validW = targetWidth?.takeIf { it > 0 }
    val validH = targetHeight?.takeIf { it > 0 }
    if (validW == null && validH == null) {
        return srcWidth to srcHeight
    }

    val rw = validW?.let { it.toDouble() / srcWidth }
    val rh = validH?.let { it.toDouble() / srcHeight }
    val rawScale = when {
        rw != null && rh != null -> max(rw, rh)
        rw != null -> rw
        else -> rh ?: 1.0
    }.coerceAtMost(1.0)

    if (rawScale >= DOWNSCALE_THRESHOLD) {
        return srcWidth to srcHeight
    }

    val dstW = (srcWidth * rawScale).toInt().coerceIn(1, srcWidth)
    val dstH = (srcHeight * rawScale).toInt().coerceIn(1, srcHeight)
    if (dstW < MIN_DOWNSCALED_DIMENSION || dstH < MIN_DOWNSCALED_DIMENSION) {
        return srcWidth to srcHeight
    }
    return dstW to dstH
}

/**
 * 根据播放倍速计算单次原生解码追帧时允许跳过的最大中间帧数。
 * 1x（及 <= 1.25x）返回 0，确保逐帧无损播放。
 */
internal fun computeMaxSkipFrames(speed: Float): Int = when {
    speed <= 1.25f -> 0
    else -> (speed.toInt() + 1).coerceIn(1, MAX_SKIP_FRAMES_CAP)
}

/** 从原生打包结果中解出当前帧结束时间戳（毫秒）。 */
internal fun unpackFrameTimestamp(packed: Long): Int = packed.toInt()

/** 从原生打包结果中解出本次推进是否触及片尾并回绕至新一轮第 1 帧。 */
internal fun unpackFrameWrapped(packed: Long): Boolean = ((packed ushr 32) and 1L) != 0L

/**
 * 基于 VSync 单调时钟的动图绝对媒体时间轴控制器，消除帧解码抖动引起的累积漂移并支持动态倍速。
 */
internal class AnimatedWebPClock(initialSpeed: Float = 1f) {
    private var speed: Float = initialSpeed.coerceAtLeast(0.1f)
    private var lastVsyncNs: Long = 0L
    private var mediaTimeMs: Double = 0.0
    private var started: Boolean = false

    var loopDurationMs: Int = 0
        private set

    fun reset(clearLoopDuration: Boolean = false) {
        started = false
        lastVsyncNs = 0L
        mediaTimeMs = 0.0
        if (clearLoopDuration) {
            loopDurationMs = 0
        }
    }

    fun pause() {
        started = false
        lastVsyncNs = 0L
    }

    fun setSpeed(newSpeed: Float, nowNs: Long? = null) {
        val sanitized = newSpeed.coerceAtLeast(0.1f)
        if (nowNs != null && started) {
            advanceTo(nowNs)
        }
        speed = sanitized
    }

    fun advanceTo(nowNs: Long): Int {
        if (!started) {
            started = true
            lastVsyncNs = nowNs
            return mediaTimeMs.toInt()
        }
        val deltaNs = (nowNs - lastVsyncNs).coerceIn(0L, MAX_FRAME_STEP_NS)
        lastVsyncNs = nowNs
        mediaTimeMs += (deltaNs / 1_000_000.0) * speed
        return mediaTimeMs.toInt()
    }

    fun onWrapped(previousLoopEndMs: Int) {
        if (previousLoopEndMs <= 0) {
            mediaTimeMs = 0.0
            return
        }
        loopDurationMs = previousLoopEndMs
        mediaTimeMs = if (mediaTimeMs >= previousLoopEndMs) {
            mediaTimeMs % previousLoopEndMs
        } else {
            0.0
        }
    }

    fun currentMediaTimeMs(): Int = mediaTimeMs.toInt()
}

private class Frame(val bitmap: Bitmap, val ahb: HardwareBuffer?) : AutoCloseable {
    override fun close() {
        ahb?.close()
        if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
    }
}

private class FrameSlot(
    val frame: Frame,
    var endTimestampMs: Int = 0,
    var wrappedFromPrev: Boolean = false,
    var isRestartAnchor: Boolean = false,
)

private fun allocFrame(w: Int, h: Int): Frame {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        HardwareBuffer.isSupported(w, h, HardwareBuffer.RGBA_8888, 1, USAGE)
    ) {
        val ahb = HardwareBuffer.create(w, h, HardwareBuffer.RGBA_8888, 1, USAGE)
        val bitmap = Bitmap.wrapHardwareBuffer(ahb, ColorSpace.get(ColorSpace.Named.SRGB))
        if (bitmap != null) {
            return Frame(bitmap, ahb)
        }
        ahb.close()
    }
    return Frame(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888), null)
}

/**
 * 基于 Rust `libwebp` 增量解码器 + 3 帧环形缓冲 + 主线程 `Choreographer` VSync 驱动的 WebP 动图 Drawable。
 */
class AnimatedWebPDrawable(
    private val source: ByteBuffer,
    targetWidth: Int? = null,
    targetHeight: Int? = null,
) : Drawable(), Animatable, AutoCloseable {
    private val srcWidth: Int
    private val srcHeight: Int
    private val loopCount: Int
    private val decodeWidth: Int
    private val decodeHeight: Int

    private val nativeLock = Any()
    private val renderLock = Any()
    private val stateLock = Any()
    private val closed = AtomicBoolean(false)

    private var decoderPtr: Long
    private val slots: Array<FrameSlot>
    private val pendingQueue = ArrayDeque<Int>()
    private var displayedIndex = 0
    private var generation = 0
    private var needResetOnNextDecode = false
    private var hasFreshInitialFrame = true

    @Volatile
    private var currentRenderFrame: Frame

    @Volatile
    private var running = false
    private var completed = false
    private var currentLoop = 0

    private val clock = AnimatedWebPClock()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    // 原生 WebPAnimDecoder 非线程安全，必须保证同一时刻最多一个协程访问解码器指针
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + decodeDispatcher)
    private var prefetchJob: Job? = null

    var playOnce: Boolean = false
    var loopForever: Boolean = true
    var onAnimationEnd: (() -> Unit)? = null

    var speed: Float = 1f
        set(value) {
            val sanitized = value.coerceAtLeast(0.1f)
            if (field == sanitized) return
            field = sanitized
            synchronized(stateLock) {
                clock.setSpeed(sanitized, System.nanoTime())
            }
        }

    private val vsyncCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running || closed.get()) return
            onVsyncTick(frameTimeNanos)
            if (running && !closed.get()) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    init {
        val ptr = nativeCreateDecoder(source)
        if (ptr == 0L) {
            throw IllegalStateException("Failed to create WebP decoder")
        }
        decoderPtr = ptr

        val info = nativeGetImageInfo(ptr)
        srcWidth = (info ushr 40).toInt()
        srcHeight = ((info ushr 16) and 0xFFFFFFL).toInt()
        loopCount = (info and 0xFFFFL).toInt()

        if (srcWidth <= 0 || srcHeight <= 0) {
            nativeDestroyDecoder(ptr)
            decoderPtr = 0L
            throw IllegalStateException("Invalid WebP canvas dimensions: ${srcWidth}x$srcHeight")
        }

        val (dw, dh) = computeWebPDecodeDimensions(srcWidth, srcHeight, targetWidth, targetHeight)
        decodeWidth = dw
        decodeHeight = dh
        val isDownscaled = dw < srcWidth || dh < srcHeight
        val bufferCount = if (isDownscaled) DOWNSCALED_BUFFER_COUNT else FULL_SIZE_BUFFER_COUNT

        slots = Array(bufferCount) { FrameSlot(allocFrame(dw, dh)) }
        try {
            val packed = decodeFrameNativeLocked(
                reset = true,
                targetMs = -1,
                maxSkip = 0,
                frame = slots[0].frame,
            )
            slots[0].endTimestampMs = unpackFrameTimestamp(packed)
            slots[0].wrappedFromPrev = false
            currentRenderFrame = slots[0].frame
        } catch (t: Throwable) {
            releaseNativeResources()
            throw t
        }
    }

    override fun draw(canvas: Canvas) {
        if (closed.get()) return
        synchronized(renderLock) {
            val frame = currentRenderFrame
            if (closed.get() || frame.bitmap.isRecycled) return
            canvas.drawBitmap(frame.bitmap, null, bounds, paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        postToMain { invalidateSelf() }
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        postToMain { invalidateSelf() }
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = srcWidth

    override fun getIntrinsicHeight(): Int = srcHeight

    override fun isRunning(): Boolean = running

    override fun start() {
        if (closed.get() || running) return
        synchronized(stateLock) {
            if (completed) return
            running = true
            if (hasFreshInitialFrame) {
                hasFreshInitialFrame = false
                clock.reset()
            }
        }
        postToMain {
            if (running && !closed.get()) {
                val choreographer = Choreographer.getInstance()
                choreographer.removeFrameCallback(vsyncCallback)
                choreographer.postFrameCallback(vsyncCallback)
            }
        }
        schedulePrefetchIfNeeded()
    }

    override fun stop() {
        if (!running) return
        running = false
        synchronized(stateLock) {
            clock.pause()
        }
        postToMain {
            Choreographer.getInstance().removeFrameCallback(vsyncCallback)
        }
    }

    /**
     * 从第 1 帧重新开始播放，兼容 [playOnce] 播完后重新触发重播的场景。
     */
    fun restart() {
        if (closed.get()) return
        stop()
        synchronized(stateLock) {
            completed = false
            currentLoop = 0
            generation++
            pendingQueue.clear()
            needResetOnNextDecode = true
            hasFreshInitialFrame = false
            clock.reset()
        }
        start()
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) {
            if (restart) {
                restart()
            }
        } else {
            stop()
        }
        return changed
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running = false
        scope.cancel()
        // 先在主线程摘除 Choreographer 回调确保不再触发新的 draw()，再异步释放原生资源
        postToMain {
            Choreographer.getInstance().removeFrameCallback(vsyncCallback)
            CoroutineScope(decodeDispatcher).launch {
                releaseNativeResources()
            }
        }
    }

    private fun onVsyncTick(frameTimeNanos: Long) {
        var needInvalidate = false
        var notifyEnd = false
        var needMorePrefetch = false

        synchronized(stateLock) {
            if (!running || completed || closed.get()) return
            var mediaMs = clock.advanceTo(frameTimeNanos)

            while (pendingQueue.isNotEmpty()) {
                val nextIdx = pendingQueue.first()
                val nextSlot = slots[nextIdx]
                if (nextSlot.isRestartAnchor) {
                    nextSlot.isRestartAnchor = false
                    clock.reset()
                    clock.advanceTo(frameTimeNanos)
                    mediaMs = 0
                    pendingQueue.removeFirst()
                    displayedIndex = nextIdx
                    currentRenderFrame = nextSlot.frame
                    needInvalidate = true
                    break
                }

                val currentEndMs = slots[displayedIndex].endTimestampMs.coerceAtLeast(16)

                if (nextSlot.wrappedFromPrev) {
                    if (mediaMs < currentEndMs) break

                    val shouldStopAfterCycle =
                        !loopForever && (playOnce || loopCount == 0 || currentLoop + 1 >= loopCount)
                    if (shouldStopAfterCycle) {
                        completed = true
                        running = false
                        notifyEnd = true
                        break
                    }

                    currentLoop++
                    clock.onWrapped(currentEndMs)
                    mediaMs = clock.currentMediaTimeMs()
                    pendingQueue.removeFirst()
                    displayedIndex = nextIdx
                    currentRenderFrame = nextSlot.frame
                    needInvalidate = true
                    break
                } else {
                    if (mediaMs < currentEndMs) break
                    pendingQueue.removeFirst()
                    displayedIndex = nextIdx
                    currentRenderFrame = nextSlot.frame
                    needInvalidate = true
                }
            }

            needMorePrefetch = running && !completed && hasFreeSlotLocked()
        }

        if (notifyEnd) {
            Choreographer.getInstance().removeFrameCallback(vsyncCallback)
            invalidateSelf()
            onAnimationEnd?.invoke()
            return
        }

        if (needInvalidate) {
            invalidateSelf()
        }
        if (needMorePrefetch) {
            schedulePrefetchIfNeeded()
        }
    }

    private fun schedulePrefetchIfNeeded() {
        if (closed.get() || !running) return
        synchronized(stateLock) {
            if (prefetchJob?.isActive == true || !hasFreeSlotLocked() || completed) return
            prefetchJob = scope.launch {
                runPrefetchLoop()
            }
        }
    }

    private fun runPrefetchLoop() {
        while (!closed.get() && running) {
            var freeIdx = -1
            var doReset = false
            var targetMs = -1
            var maxSkip = 0
            var jobGen = 0

            synchronized(stateLock) {
                if (!running || completed || closed.get()) return
                freeIdx = findFreeSlotIndexLocked()
                if (freeIdx < 0) return

                jobGen = generation
                if (needResetOnNextDecode) {
                    needResetOnNextDecode = false
                    doReset = true
                    targetMs = -1
                    maxSkip = 0
                } else {
                    maxSkip = computeMaxSkipFrames(speed)
                    val currentMediaMs = clock.currentMediaTimeMs()
                    val tailEndMs = pendingQueue.lastOrNull()?.let { slots[it].endTimestampMs }
                        ?: slots[displayedIndex].endTimestampMs
                    targetMs = if (maxSkip > 0 && currentMediaMs > tailEndMs) {
                        currentMediaMs
                    } else {
                        -1
                    }
                }
            }

            val packed = try {
                decodeFrameNativeLocked(
                    reset = doReset,
                    targetMs = targetMs,
                    maxSkip = maxSkip,
                    frame = slots[freeIdx].frame,
                )
            } catch (_: Throwable) {
                return
            }

            if (closed.get()) return

            val ts = unpackFrameTimestamp(packed)
            val wrapped = unpackFrameWrapped(packed)

            synchronized(stateLock) {
                if (closed.get() || jobGen != generation) {
                    continue
                }
                val slot = slots[freeIdx]
                slot.endTimestampMs = ts
                slot.wrappedFromPrev = wrapped && !doReset
                slot.isRestartAnchor = doReset
                pendingQueue.addLast(freeIdx)
                if (!hasFreeSlotLocked()) return
            }
        }
    }

    private fun hasFreeSlotLocked(): Boolean = pendingQueue.size + 1 < slots.size

    private fun findFreeSlotIndexLocked(): Int {
        for (i in slots.indices) {
            if (i != displayedIndex && !pendingQueue.contains(i)) {
                return i
            }
        }
        return -1
    }

    private fun decodeFrameNativeLocked(
        reset: Boolean,
        targetMs: Int,
        maxSkip: Int,
        frame: Frame,
    ): Long = synchronized(nativeLock) {
        val ptr = decoderPtr
        if (ptr == 0L || closed.get()) return 0L
        val ahb = frame.ahb
        if (ahb != null) {
            nativeDecodeNextFrameZeroCopy(ptr, reset, targetMs, maxSkip, ahb)
        } else {
            nativeDecodeNextFrame(ptr, reset, targetMs, maxSkip, frame.bitmap)
        }
    }

    private fun releaseNativeResources() {
        synchronized(nativeLock) {
            if (decoderPtr != 0L) {
                nativeDestroyDecoder(decoderPtr)
                decoderPtr = 0L
            }
            synchronized(renderLock) {
                slots.forEach { it.frame.close() }
            }
        }
    }

    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}

private external fun nativeCreateDecoder(source: ByteBuffer): Long
private external fun nativeGetImageInfo(decoder: Long): Long
private external fun nativeDecodeNextFrame(
    decoder: Long,
    reset: Boolean,
    targetMs: Int,
    maxSkip: Int,
    bitmap: Bitmap,
): Long

private external fun nativeDecodeNextFrameZeroCopy(
    decoder: Long,
    reset: Boolean,
    targetMs: Int,
    maxSkip: Int,
    ahb: HardwareBuffer,
): Long

private external fun nativeDestroyDecoder(decoder: Long)
