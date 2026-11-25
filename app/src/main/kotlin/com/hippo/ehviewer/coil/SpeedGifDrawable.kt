package com.hippo.ehviewer.coil

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Choreographer
import java.lang.SuppressWarnings

/**
 * A GIF drawable that can change playback speed and loop mode at runtime.
 *
 * Android's AnimatedImageDrawable does not expose a playback speed, so playback mode uses this
 * Movie-based drawable for GIFs. Movie is old but is still the only platform API that allows
 * arbitrary frame times without decoding the whole GIF ourselves.
 */
@Suppress("DEPRECATION")
class SpeedGifDrawable(private val movie: Movie) : Drawable(), Animatable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var playing = false
    private var startTime = 0L
    private var lastMovieTime = 0
    private var endNotified = false
    private val choreographer = Choreographer.getInstance()

    var speed = 1f
        set(value) {
            val updated = if (value.isFinite()) value.coerceIn(0.1f, 5f) else 1f
            if (updated == field) return
            val now = SystemClock.uptimeMillis()
            val elapsed = (now - startTime).coerceAtLeast(0)
            val currentMovieTime = (elapsed * field).toLong()
            startTime = now - (currentMovieTime / updated).toLong()
            field = updated
            invalidateSelf()
        }
    var looping = true
    var onAnimationEnd: (() -> Unit)? = null

    private val frameCallback: Choreographer.FrameCallback = Choreographer.FrameCallback {
        if (!playing) return@FrameCallback
        invalidateSelf()
        choreographer.postFrameCallback(frameCallback)
    }

    override fun getIntrinsicWidth() = movie.width()

    override fun getIntrinsicHeight() = movie.height()

    override fun draw(canvas: Canvas) {
        val duration = movie.duration()
        if (duration <= 0) {
            movie.setTime(0)
            drawMovie(canvas)
            return
        }
        if (!playing) {
            // 停播或等待切页时保留最后一帧画面，避免瞬间黑屏消失。
            movie.setTime(lastMovieTime.coerceIn(0, duration - 1))
            drawMovie(canvas)
            return
        }
        val elapsed = ((SystemClock.uptimeMillis() - startTime) * speed.coerceAtLeast(0.05f)).toLong()
        if (!looping && elapsed >= duration) {
            lastMovieTime = duration - 1
            movie.setTime(lastMovieTime)
            drawMovie(canvas)
            playing = false
            choreographer.removeFrameCallback(frameCallback)
            if (!endNotified) {
                endNotified = true
                onAnimationEnd?.invoke()
            }
            return
        }
        lastMovieTime = (elapsed % duration).toInt()
        movie.setTime(lastMovieTime)
        drawMovie(canvas)
    }

    private fun drawMovie(canvas: Canvas) {
        val width = movie.width()
        val height = movie.height()
        if (width <= 0 || height <= 0) return
        val save = canvas.save()
        canvas.scale(bounds.width().toFloat() / width, bounds.height().toFloat() / height)
        movie.draw(canvas, 0f, 0f, paint)
        canvas.restoreToCount(save)
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        // 可见性变化仅挂起/恢复帧回调，不私自改变 playing 或重置 endNotified，避免绕过暂停与二次触发切页。
        choreographer.removeFrameCallback(frameCallback)
        if (visible && playing) {
            invalidateSelf()
            choreographer.postFrameCallback(frameCallback)
        }
        return changed
    }

    override fun isRunning() = playing

    override fun start() {
        if (!isVisible || playing) return
        playing = true
        endNotified = false
        startTime = SystemClock.uptimeMillis()
        lastMovieTime = 0
        invalidateSelf()
        choreographer.removeFrameCallback(frameCallback)
        choreographer.postFrameCallback(frameCallback)
    }

    /** 显式从头重新播放（即使此前已播放或被预组合起播，也会重置时间轴与结束标志）。 */
    fun restart() {
        stop()
        playing = true
        endNotified = false
        lastMovieTime = 0
        startTime = SystemClock.uptimeMillis()
        invalidateSelf()
        if (isVisible) {
            choreographer.postFrameCallback(frameCallback)
        }
    }

    override fun stop() {
        playing = false
        choreographer.removeFrameCallback(frameCallback)
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun setAlpha(alpha: Int) {
        if (paint.alpha != alpha) {
            paint.alpha = alpha
            invalidateSelf()
        }
    }

    override fun getAlpha() = paint.alpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        if (paint.colorFilter != colorFilter) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }
    }

    override fun getColorFilter(): ColorFilter? = paint.colorFilter
}
