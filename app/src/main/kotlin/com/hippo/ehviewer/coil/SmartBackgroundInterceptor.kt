package com.hippo.ehviewer.coil

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.unit.IntRect
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 像素读取抽象接口，解耦 Android `Bitmap` 以支持纯 JVM 单元测试。
 */
internal fun interface PixelReader {
    fun getPixel(x: Int, y: Int): Int
}

private class ColorBucket {
    var count: Int = 0
    var sumR: Long = 0
    var sumG: Long = 0
    var sumB: Long = 0

    fun add(r: Int, g: Int, b: Int) {
        count++
        sumR += r
        sumG += g
        sumB += b
    }

    val avgR: Int get() = (sumR / count).toInt()
    val avgG: Int get() = (sumG / count).toInt()
    val avgB: Int get() = (sumB / count).toInt()
}

private const val SAMPLES_PER_EDGE = 24
private const val MIN_ALPHA = 128
private const val BUCKET_MERGE_DIFF = 24
private const val HIGH_CONFIDENCE_RATIO = 0.65f
private const val LOW_CONFIDENCE_RATIO = 0.35f
private const val HIGH_SATURATION_THRESHOLD = 0.45f
private const val MIXED_EDGE_DARKEN_FACTOR = 0.80f

/**
 * 从裁白边后的可见区域四边采样像素并提取平滑自然的阅读器背景色（ARGB）。
 *
 * - 当四边存在大面积统一底色时忠实保留并吸附纯白/纯黑纸面；
 * - 当四边色彩杂乱或高饱和时适度向等亮灰度柔化并压暗，避免视觉刺眼。
 *
 * @return 提取出的 ARGB 颜色值；当图像尺寸非法或四边几乎全透明时返回 `null` 由调用方回退默认背景。
 */
internal fun extractSmartBackgroundColor(
    width: Int,
    height: Int,
    left: Int = 0,
    top: Int = 0,
    right: Int = width,
    bottom: Int = height,
    reader: PixelReader,
): Int? {
    if (width <= 0 || height <= 0) return null
    val l = left.coerceIn(0, width)
    val t = top.coerceIn(0, height)
    val r = right.coerceIn(l, width)
    val b = bottom.coerceIn(t, height)
    val w = r - l
    val h = b - t
    if (w <= 0 || h <= 0) return null

    // 轻微内缩 1%（最多 4px），跳过扫描本或 JPEG 边缘常见的黑线与压缩噪点
    val rawInsetX = (w * 0.01f).toInt().coerceIn(0, 4)
    val rawInsetY = (h * 0.01f).toInt().coerceIn(0, 4)
    val insetX = if (w > rawInsetX * 2) rawInsetX else 0
    val insetY = if (h > rawInsetY * 2) rawInsetY else 0

    val sx0 = l + insetX
    val sy0 = t + insetY
    val sx1 = (r - 1 - insetX).coerceAtLeast(sx0)
    val sy1 = (b - 1 - insetY).coerceAtLeast(sy0)

    var validCount = 0
    var totalR = 0L
    var totalG = 0L
    var totalB = 0L
    val buckets = HashMap<Int, ColorBucket>(32)

    fun samplePixel(x: Int, y: Int) {
        val argb = reader.getPixel(x, y)
        val alpha = (argb ushr 24) and 0xFF
        if (alpha < MIN_ALPHA) return
        val cr = (argb ushr 16) and 0xFF
        val cg = (argb ushr 8) and 0xFF
        val cb = argb and 0xFF
        validCount++
        totalR += cr
        totalG += cg
        totalB += cb
        val key = ((cr ushr 4) shl 8) or ((cg ushr 4) shl 4) or (cb ushr 4)
        buckets.getOrPut(key, ::ColorBucket).add(cr, cg, cb)
    }

    fun sampleHorizontalEdge(y: Int) {
        val span = sx1 - sx0 + 1
        val count = minOf(SAMPLES_PER_EDGE, span)
        if (count <= 1) {
            samplePixel(sx0, y)
            return
        }
        val maxOffset = span - 1
        val denom = count - 1
        for (i in 0 until count) {
            samplePixel(sx0 + (maxOffset * i) / denom, y)
        }
    }

    fun sampleVerticalEdge(x: Int) {
        val span = sy1 - sy0 + 1
        val count = minOf(SAMPLES_PER_EDGE, span)
        if (count <= 1) {
            samplePixel(x, sy0)
            return
        }
        val maxOffset = span - 1
        val denom = count - 1
        for (i in 0 until count) {
            samplePixel(x, sy0 + (maxOffset * i) / denom)
        }
    }

    sampleHorizontalEdge(sy0)
    if (sy1 != sy0) {
        sampleHorizontalEdge(sy1)
    }
    if (sy1 > sy0) {
        sampleVerticalEdge(sx0)
        if (sx1 != sx0) {
            sampleVerticalEdge(sx1)
        }
    }

    if (validCount == 0 || buckets.isEmpty()) return null

    var primaryBucket: ColorBucket? = null
    for (bucket in buckets.values) {
        if (primaryBucket == null || bucket.count > primaryBucket.count) {
            primaryBucket = bucket
        }
    }
    val primary = primaryBucket ?: return null
    val pR = primary.avgR
    val pG = primary.avgG
    val pB = primary.avgB

    var mergedCount = 0
    var mergedR = 0L
    var mergedG = 0L
    var mergedB = 0L
    for (bucket in buckets.values) {
        if (abs(bucket.avgR - pR) <= BUCKET_MERGE_DIFF &&
            abs(bucket.avgG - pG) <= BUCKET_MERGE_DIFF &&
            abs(bucket.avgB - pB) <= BUCKET_MERGE_DIFF
        ) {
            mergedCount += bucket.count
            mergedR += bucket.sumR
            mergedG += bucket.sumG
            mergedB += bucket.sumB
        }
    }

    val domR = (mergedR / mergedCount).toInt()
    val domG = (mergedG / mergedCount).toInt()
    val domB = (mergedB / mergedCount).toInt()
    val dominantRatio = mergedCount.toFloat() / validCount

    var outR: Int
    var outG: Int
    var outB: Int

    when {
        dominantRatio < LOW_CONFIDENCE_RATIO -> {
            val allR = (totalR / validCount).toInt()
            val allG = (totalG / validCount).toInt()
            val allB = (totalB / validCount).toInt()
            val luma = rec709Luma(allR, allG, allB)
            outR = blendTowardLumaAndScale(allR, luma, 0.50f, MIXED_EDGE_DARKEN_FACTOR)
            outG = blendTowardLumaAndScale(allG, luma, 0.50f, MIXED_EDGE_DARKEN_FACTOR)
            outB = blendTowardLumaAndScale(allB, luma, 0.50f, MIXED_EDGE_DARKEN_FACTOR)
        }
        dominantRatio < HIGH_CONFIDENCE_RATIO -> {
            val maxC = maxOf(domR, domG, domB)
            val minC = minOf(domR, domG, domB)
            val saturation = if (maxC > 0) (maxC - minC) / maxC.toFloat() else 0f
            if (saturation > HIGH_SATURATION_THRESHOLD) {
                val luma = rec709Luma(domR, domG, domB)
                outR = blendTowardLumaAndScale(domR, luma, 0.45f, 1f)
                outG = blendTowardLumaAndScale(domG, luma, 0.45f, 1f)
                outB = blendTowardLumaAndScale(domB, luma, 0.45f, 1f)
            } else {
                outR = domR
                outG = domG
                outB = domB
            }
        }
        else -> {
            outR = domR
            outG = domG
            outB = domB
        }
    }

    val maxC = maxOf(outR, outG, outB)
    val minC = minOf(outR, outG, outB)
    if (outR >= 248 && outG >= 248 && outB >= 248 && maxC - minC <= 6) {
        return 0xFFFFFFFF.toInt()
    }
    if (outR <= 8 && outG <= 8 && outB <= 8 && maxC - minC <= 6) {
        return 0xFF000000.toInt()
    }

    return (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
}

private fun rec709Luma(r: Int, g: Int, b: Int): Float = 0.213f * r + 0.715f * g + 0.072f * b

private fun blendTowardLumaAndScale(channel: Int, luma: Float, grayFactor: Float, scale: Float): Int {
    val blended = channel + (luma - channel) * grayFactor
    return (blended * scale).roundToInt().coerceIn(0, 255)
}

/**
 * 从软件 `Bitmap` 中提取裁白边后的智能背景色；对已回收或硬件纹理 `Bitmap` 安全返回 `null`。
 */
fun extractSmartBackgroundColor(bitmap: Bitmap, rect: IntRect): Int? {
    if (bitmap.isRecycled) return null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
        return null
    }
    return extractSmartBackgroundColor(
        width = bitmap.width,
        height = bitmap.height,
        left = rect.left,
        top = rect.top,
        right = rect.right,
        bottom = rect.bottom,
        reader = bitmap::getPixel,
    )
}

/**
 * 将阅读器“灰度”和“反色”滤镜同步应用到提取出的背景色，保持背景与画面视觉一致。
 */
fun applyReaderColorFilters(argb: Int, grayScale: Boolean, invertedColors: Boolean): Int {
    var r = (argb ushr 16) and 0xFF
    var g = (argb ushr 8) and 0xFF
    var b = argb and 0xFF
    if (grayScale) {
        val luma = rec709Luma(r, g, b).roundToInt().coerceIn(0, 255)
        r = luma
        g = luma
        b = luma
    }
    if (invertedColors) {
        r = 255 - r
        g = 255 - g
        b = 255 - b
    }
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * Coil 解码拦截器：在裁白边与转硬件纹理之前对软件 `Bitmap` 采样四边主色，供阅读器智能背景模式复用。
 */
object SmartBackgroundInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val result = chain.proceed()
        if (result is SuccessResult) {
            val image = result.image
            if (image is BitmapImageWithExtraInfo) {
                val color = extractSmartBackgroundColor(image.image.bitmap, image.rect)
                if (color != null) {
                    return result.copy(image = image.copy(backgroundColor = color))
                }
            }
        }
        return result
    }
}
