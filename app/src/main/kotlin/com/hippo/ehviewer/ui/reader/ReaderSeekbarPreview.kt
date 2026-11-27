package com.hippo.ehviewer.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.BitmapImage
import coil3.DrawableImage
import com.ehviewer.core.files.openFileDescriptor
import com.hippo.ehviewer.coil.BitmapImageWithExtraInfo
import com.hippo.ehviewer.gallery.PageLoader
import com.hippo.ehviewer.image.ByteBufferSource
import com.hippo.ehviewer.image.PathSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal const val SEEKBAR_PREVIEW_MAX_DIM_PX = 256
private const val SEEKBAR_PREVIEW_CACHE_MAX_ENTRIES = 48
private const val SEEKBAR_PREVIEW_DEBOUNCE_MILLIS = 60L

/**
 * 计算进度条缩略图采样率（2 的幂次），将最长边约束在 [maxDim] 附近以降低拖动解码开销。
 */
internal fun calculatePreviewInSampleSize(
    width: Int,
    height: Int,
    maxDim: Int = SEEKBAR_PREVIEW_MAX_DIM_PX,
): Int {
    if (width <= 0 || height <= 0 || maxDim <= 0) return 1
    var inSampleSize = 1
    val longest = maxOf(width, height)
    while (longest / (inSampleSize * 2) >= maxDim) {
        inSampleSize *= 2
    }
    return inSampleSize
}

/**
 * 计算缩略图浮窗在进度条轨道上的水平比例位置 `[0f, 1f]`，RTL 阅读模式下自动反向镜像。
 */
internal fun calculatePreviewBubbleOffsetFraction(
    pageOneBased: Int,
    totalPages: Int,
    isRtl: Boolean,
): Float {
    if (totalPages <= 1) return 0.5f
    val clampedPage = pageOneBased.coerceIn(1, totalPages)
    val raw = (clampedPage - 1).toFloat() / (totalPages - 1).toFloat()
    return if (isRtl) 1f - raw else raw
}

internal class SeekbarPreviewCache(
    private val maxEntries: Int = SEEKBAR_PREVIEW_CACHE_MAX_ENTRIES,
) {
    private val lock = Any()
    private val cache = object : LinkedHashMap<Int, Bitmap>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?): Boolean = size > maxEntries
    }

    fun get(index: Int): Bitmap? = synchronized(lock) {
        cache[index]?.takeUnless { it.isRecycled }
    }

    fun put(index: Int, bitmap: Bitmap) {
        synchronized(lock) {
            cache[index] = bitmap
        }
    }
}

/**
 * 进度条拖动期间浮在进度条上方的页面小图预览卡片：
 * 仅从当前内存页缓存、本地文件或磁盘缓存解码，未缓存时优雅展示目标页码占位，不触发额外网络请求。
 */
@Composable
fun ReaderSeekbarPreviewCard(
    pageLoader: PageLoader,
    pageOneBased: Int,
    totalPages: Int,
    modifier: Modifier = Modifier,
) {
    val cache = remember(pageLoader) { SeekbarPreviewCache() }
    val pageIndex = (pageOneBased - 1).coerceIn(0, (pageLoader.size - 1).coerceAtLeast(0))
    val previewBitmap by produceState<Bitmap?>(
        initialValue = cache.get(pageIndex),
        key1 = pageLoader,
        key2 = pageIndex,
    ) {
        cache.get(pageIndex)?.let {
            value = it
            return@produceState
        }
        delay(SEEKBAR_PREVIEW_DEBOUNCE_MILLIS)
        val decoded = withContext(Dispatchers.IO) {
            loadCachedPreviewBitmap(pageLoader, pageIndex)
        }
        if (decoded != null) {
            cache.put(pageIndex, decoded)
        }
        value = decoded
    }

    Surface(
        modifier = modifier.width(104.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(
            modifier = Modifier
                .padding(4.dp)
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = previewBitmap
            if (bitmap != null && !bitmap.isRecycled) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Image,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "$pageOneBased",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = "$pageOneBased / $totalPages",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun loadCachedPreviewBitmap(pageLoader: PageLoader, index: Int): Bitmap? {
    pageLoader.peekCachedImage(index)?.let { cachedImage ->
        if (cachedImage.pin()) {
            try {
                createThumbnailFromCoilImage(cachedImage.innerImage)?.let { return it }
            } finally {
                cachedImage.unpin()
            }
        }
    }
    val source = pageLoader.openCachedPreviewSource(index) ?: return null
    return source.use { src ->
        when (src) {
            is PathSource -> decodeSampledBitmapFromPath(src)
            is ByteBufferSource -> decodeSampledBitmapFromByteBuffer(src)
        }
    }
}

private fun createThumbnailFromCoilImage(coilImage: coil3.Image?): Bitmap? = runCatching {
    val unwrapped = (coilImage as? BitmapImageWithExtraInfo)?.image ?: coilImage ?: return null
    when (unwrapped) {
        is BitmapImage -> {
            val src = unwrapped.bitmap
            if (src.isRecycled || src.width <= 0 || src.height <= 0) return null
            scaleBitmapToPreview(src)
        }
        is DrawableImage -> {
            val drawable = unwrapped.drawable
            if (drawable is BitmapDrawable) {
                val bmp = drawable.bitmap
                if (bmp != null && !bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
                    return scaleBitmapToPreview(bmp)
                }
            }
            val rawW = drawable.intrinsicWidth.takeIf { it > 0 } ?: SEEKBAR_PREVIEW_MAX_DIM_PX
            val rawH = drawable.intrinsicHeight.takeIf { it > 0 } ?: SEEKBAR_PREVIEW_MAX_DIM_PX
            val scale = minOf(1f, SEEKBAR_PREVIEW_MAX_DIM_PX.toFloat() / maxOf(rawW, rawH))
            val targetW = (rawW * scale).toInt().coerceAtLeast(1)
            val targetH = (rawH * scale).toInt().coerceAtLeast(1)
            val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            drawable.setBounds(0, 0, targetW, targetH)
            drawable.draw(canvas)
            out
        }
        else -> null
    }
}.getOrNull()

private fun scaleBitmapToPreview(src: Bitmap): Bitmap {
    val longest = maxOf(src.width, src.height)
    if (longest <= SEEKBAR_PREVIEW_MAX_DIM_PX) {
        return src.copy(Bitmap.Config.ARGB_8888, false)
    }
    val ratio = SEEKBAR_PREVIEW_MAX_DIM_PX.toFloat() / longest.toFloat()
    val dstW = (src.width * ratio).toInt().coerceAtLeast(1)
    val dstH = (src.height * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(src, dstW, dstH, true)
}

private fun decodeSampledBitmapFromPath(src: PathSource): Bitmap? = runCatching {
    src.source.openFileDescriptor("r").use { pfd ->
        val fd = pfd.fileDescriptor
        val boundsOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFileDescriptor(fd, null, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = calculatePreviewInSampleSize(boundsOpts.outWidth, boundsOpts.outHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeFileDescriptor(fd, null, decodeOpts)
    }
}.getOrNull()

private fun decodeSampledBitmapFromByteBuffer(src: ByteBufferSource): Bitmap? = runCatching {
    val dup = src.source.duplicate().apply { rewind() }
    val bytes = ByteArray(dup.remaining())
    dup.get(bytes)
    val boundsOpts = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
    if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null
    val decodeOpts = BitmapFactory.Options().apply {
        inSampleSize = calculatePreviewInSampleSize(boundsOpts.outWidth, boundsOpts.outHeight)
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
}.getOrNull()
