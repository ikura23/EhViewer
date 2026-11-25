package com.hippo.ehviewer.ui.main

import android.content.Context
import android.graphics.Movie
import android.graphics.drawable.Animatable
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.DrawableImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.ehviewer.core.database.model.DownloadInfo
import com.google.accompanist.drawablepainter.DrawablePainter
import com.hippo.ehviewer.coil.AnimatedWebPDrawable
import com.hippo.ehviewer.coil.SpeedGifDrawable
import com.hippo.ehviewer.download.LocalGalleryScanner
import com.hippo.ehviewer.download.downloadDir
import com.hippo.ehviewer.download.isAnimatedImageFile
import com.hippo.ehviewer.download.resolveDownloadLocationPath
import com.hippo.ehviewer.gallery.LocalFolderPageLoader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 所有预览场景（下载页、列表/网格卡片、详情页封面及预览网格）的默认动图播放倍率。
 */
const val PREVIEW_ANIMATED_DEFAULT_SPEED = 2f

/**
 * 预览场景允许直接加载播映的本地动图体积上限（16 MiB），避免超大原图拖垮列表滑动内存。
 */
const val MAX_ANIMATED_PREVIEW_BYTES = 16L * 1024 * 1024

private val PREVIEW_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif")
private val PADDED_PAGE_REGEX = Regex("^(\\d+)\\.([a-zA-Z0-9]+)$")

/**
 * 通用预览动图/图片显示组件：
 * - 若数据源为 GIF（`File` 或 `Uri` 且体积不超限），优先在 IO 线程解码 `Movie` 并包装为 `SpeedGifDrawable`，
 *   以支持 2x 等实时倍率调节；
 * - 若为动态 WebP，拦截生成的 `AnimatedWebPDrawable` 并禁用跨场景内存缓存共享，
 *   防止一处离屏 `onDispose` 停播影响另一处并发播放，同时实时同步 `speed` 与循环状态；
 * - 常规静态图正常走 Coil 内存/磁盘缓存路径。
 */
@Composable
fun AnimatedPreviewImage(
    data: Any,
    cacheKey: String,
    sceneTag: String,
    modifier: Modifier = Modifier,
    speed: Float = PREVIEW_ANIMATED_DEFAULT_SPEED,
    contentScale: ContentScale = ContentScale.Crop,
    onSuccess: ((AsyncImagePainter.State.Success) -> Unit)? = null,
    onError: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val gifMovieResult by produceState<GifDecodeState>(GifDecodeState.Pending, data, cacheKey) {
        value = withContext(Dispatchers.IO) {
            decodePreviewGifMovieIfNeeded(context, data)
        }
    }

    when (val gifState = gifMovieResult) {
        GifDecodeState.Pending -> {
            // 等待极速文件头探测时暂不做重复解码，避免 GIF 被系统 AnimatedImageDecoder 抢先解码后再重绘闪烁
        }
        is GifDecodeState.Gif -> {
            val speedGif = remember(gifState.movie) {
                SpeedGifDrawable(gifState.movie).apply {
                    looping = true
                    this.speed = speed
                }
            }
            val gifPainter = remember(speedGif) { DrawablePainter(speedGif) }
            DisposableEffect(speedGif, speed) {
                speedGif.looping = true
                speedGif.speed = speed
                speedGif.setVisible(true, false)
                speedGif.start()
                onDispose {
                    speedGif.stop()
                    speedGif.setVisible(false, false)
                }
            }
            Image(
                painter = gifPainter,
                contentDescription = null,
                modifier = modifier,
                contentScale = contentScale,
            )
        }
        GifDecodeState.NotGif -> {
            val request = remember(data, cacheKey, sceneTag, context) {
                val builder = ImageRequest.Builder(context)
                    .data(data)
                    .diskCacheKey(cacheKey)
                    .crossfade(false)
                // 对可能解出 AnimatedWebPDrawable 的动图禁用跨界面内存实例共享，避免互抢播放生命周期
                if (isLikelyAnimatedSource(data)) {
                    builder.memoryCachePolicy(CachePolicy.DISABLED)
                } else {
                    builder.memoryCacheKey("$sceneTag:$cacheKey")
                }
                builder.build()
            }
            val painter = rememberAsyncImagePainter(
                model = request,
                onSuccess = onSuccess,
            )
            val painterState by painter.state.collectAsState()
            if (painterState is AsyncImagePainter.State.Error && onError != null) {
                onError()
                return
            }
            val drawable = ((painterState as? AsyncImagePainter.State.Success)?.result?.image as? DrawableImage)?.drawable
            DisposableEffect(drawable, speed) {
                when (drawable) {
                    is AnimatedWebPDrawable -> {
                        drawable.loopForever = true
                        drawable.speed = speed
                    }
                    is SpeedGifDrawable -> {
                        drawable.looping = true
                        drawable.speed = speed
                    }
                }
                val animatable = drawable as? Animatable
                if (animatable != null) {
                    drawable.setVisible(true, false)
                    animatable.start()
                }
                onDispose {
                    val anim = drawable as? Animatable
                    if (anim != null) {
                        anim.stop()
                        drawable.setVisible(false, false)
                    }
                }
            }
            Image(
                painter = painter,
                contentDescription = null,
                modifier = modifier,
                contentScale = contentScale,
            )
        }
    }
}

private sealed interface GifDecodeState {
    data object Pending : GifDecodeState
    data class Gif(
        @Suppress("DEPRECATION") val movie: Movie,
    ) : GifDecodeState
    data object NotGif : GifDecodeState
}

private fun isLikelyAnimatedSource(data: Any): Boolean = when (data) {
    is File -> data.isFile && data.length() <= MAX_ANIMATED_PREVIEW_BYTES && isAnimatedImageFile(data)
    is Uri -> data.toString().substringBefore('?').substringAfterLast('.', "").lowercase() in setOf("webp", "gif")
    else -> false
}

@Suppress("DEPRECATION")
private fun decodePreviewGifMovieIfNeeded(context: Context, data: Any): GifDecodeState {
    val bytes = readGifBytesIfAnimated(context, data) ?: return GifDecodeState.NotGif
    val movie = runCatching {
        Movie.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull() ?: return GifDecodeState.NotGif
    if (movie.duration() <= 0) return GifDecodeState.NotGif
    return GifDecodeState.Gif(movie)
}

private fun readGifBytesIfAnimated(context: Context, data: Any): ByteArray? = runCatching {
    when (data) {
        is File -> {
            if (!data.isFile || data.length() <= 0L || data.length() > MAX_ANIMATED_PREVIEW_BYTES) return null
            val header = ByteArray(16)
            val headerLen = data.inputStream().use { it.read(header) }
            if (headerLen < 6 || !isGifHeader(header, headerLen)) return null
            data.readBytes()
        }
        is Uri -> {
            val stream = context.contentResolver.openInputStream(data) ?: return null
            stream.use { input ->
                val header = ByteArray(16)
                val headerLen = input.read(header)
                if (headerLen < 6 || !isGifHeader(header, headerLen)) return null
                val remaining = input.readBytes()
                if (headerLen + remaining.size > MAX_ANIMATED_PREVIEW_BYTES) return null
                ByteArray(headerLen + remaining.size).apply {
                    System.arraycopy(header, 0, this, 0, headerLen)
                    System.arraycopy(remaining, 0, this, headerLen, remaining.size)
                }
            }
        }
        else -> null
    }
}.getOrNull()

private fun isGifHeader(bytes: ByteArray, length: Int): Boolean =
    length >= 6 &&
        bytes[0] == 'G'.code.toByte() &&
        bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() &&
        bytes[3] == '8'.code.toByte() &&
        (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) &&
        bytes[5] == 'a'.code.toByte()

/**
 * 解析下载条目的本地物理文件夹路径（兼容普通相对目录名、绝对路径及有全盘权限时的 SAF `content://`）。
 */
fun resolvePhysicalDownloadDir(info: DownloadInfo): File? = runCatching {
    val rawPath = info.downloadDir?.toString() ?: return@runCatching null
    val candidatePath: String? = if (rawPath.startsWith("content:") && LocalGalleryScanner.hasAllFilesAccess()) {
        resolveDownloadLocationPath(rawPath, Environment.getExternalStorageDirectory().absolutePath)
    } else if (rawPath.startsWith("/")) {
        rawPath
    } else {
        null
    }
    val physicalPath = candidatePath ?: return@runCatching null
    File(physicalPath).takeIf { it.isDirectory }
}.getOrNull()

/**
 * 构建下载条目的页索引映射（0-based 页序号 -> 本地图片 `File`），
 * 供详情页预览网格按页查找本地已下载的动图文件直接播放。
 */
fun buildLocalPageFileIndex(info: DownloadInfo): Map<Int, File> = runCatching {
    val dir = resolvePhysicalDownloadDir(info) ?: return@runCatching emptyMap()
    val files = dir.listFiles()?.filter { file ->
        file.isFile &&
            !file.name.equals("thumb.webp", ignoreCase = true) &&
            !file.name.endsWith(".tmp", ignoreCase = true) &&
            file.extension.lowercase() in PREVIEW_IMAGE_EXTENSIONS
    } ?: return@runCatching emptyMap()
    if (files.isEmpty()) return@runCatching emptyMap()

    val indexed = LinkedHashMap<Int, File>(files.size)
    var hasPaddedNames = false
    for (file in files) {
        val match = PADDED_PAGE_REGEX.matchEntire(file.name) ?: continue
        val pageOneBased = match.groupValues[1].toIntOrNull() ?: continue
        if (pageOneBased >= 1) {
            indexed[pageOneBased - 1] = file
            hasPaddedNames = true
        }
    }
    if (hasPaddedNames && indexed.size == files.size) {
        return@runCatching indexed
    }
    // 对非标准命名或混合命名的目录，补全自然排序后按顺序映射到页序号
    val sorted = files.sortedWith { a, b ->
        LocalFolderPageLoader.NATURAL_NAME_COMPARATOR.compare(a.name, b.name)
    }
    sorted.forEachIndexed { index, file ->
        indexed.putIfAbsent(index, file)
    }
    indexed
}.getOrDefault(emptyMap())

/**
 * 查找目录或 SAF 目录下第一张可用本地图片（优先用于探测是否为动图）。
 */
internal fun findFirstImageFileInDir(dir: File): File? {
    val files = dir.listFiles()?.filter { file ->
        file.isFile &&
            !file.name.equals("thumb.webp", ignoreCase = true) &&
            !file.name.endsWith(".tmp", ignoreCase = true) &&
            file.extension.lowercase() in PREVIEW_IMAGE_EXTENSIONS
    } ?: return null
    return files.minWithOrNull { a, b ->
        LocalFolderPageLoader.NATURAL_NAME_COMPARATOR.compare(a.name, b.name)
    }
}

internal fun findFirstAnimatedImageInDir(dir: File): File? {
    val exactCandidates = listOf("00000001.webp", "00000001.gif", "001.webp", "001.gif")
    for (name in exactCandidates) {
        val f = File(dir, name)
        if (f.isFile && f.length() <= MAX_ANIMATED_PREVIEW_BYTES && isAnimatedImageFile(f)) {
            return f
        }
    }
    val first = findFirstImageFileInDir(dir) ?: return null
    return first.takeIf { it.length() <= MAX_ANIMATED_PREVIEW_BYTES && isAnimatedImageFile(it) }
}
