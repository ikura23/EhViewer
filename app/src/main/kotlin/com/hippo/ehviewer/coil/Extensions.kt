package com.hippo.ehviewer.coil

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.paging.compose.LazyPagingItems
import coil3.decode.BlackholeDecoder
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.SizeResolver
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.model.GalleryPreview
import com.ehviewer.core.model.V2GalleryPreview
import com.hippo.ehviewer.client.getThumbKey
import com.hippo.ehviewer.client.getV2PreviewKey
import com.hippo.ehviewer.client.thumbUrl
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.ktbuilder.execute

/**
 * 为画廊封面生成隔离后的缓存键：
 * - 远程 `thumbKey`（含 `/` 分隔路径）本身全局唯一，直接复用；
 * - 本地导入仅有相对文件名（如 `00000001.jpg`），必须拼接 `gid` 隔离，防止不同画廊互相串图。
 */
fun resolveGalleryThumbCacheKey(gid: Long, thumbKey: String): String =
    if ('/' in thumbKey) thumbKey else "local-thumb:$gid:$thumbKey"

// Load in original size so the memory cache can be reused for preload requests
fun ImageRequest.Builder.ehUrl(info: GalleryInfo) = apply {
    // Imported local galleries have no remote thumbKey. Let the caller render a local
    // placeholder instead of crashing the whole downloads screen.
    val key = info.thumbKey ?: return@apply
    val cacheKey = resolveGalleryThumbCacheKey(info.gid, key)
    data(info.thumbUrl)
    size(SizeResolver.ORIGINAL)
    val downloadInfo = (info as? DownloadInfo) ?: DownloadManager.getDownloadInfo(info.gid)
    if (downloadInfo != null) {
        downloadInfo(downloadInfo)
    }
    memoryCacheKey(cacheKey)
    diskCacheKey(cacheKey)
}

// Load in original size so the memory cache can be reused for preload requests
fun ImageRequest.Builder.ehPreview(preview: GalleryPreview) = apply {
    with(preview) {
        val key = if (this is V2GalleryPreview) getV2PreviewKey(url) else getThumbKey(url)
        data(url)
        size(SizeResolver.ORIGINAL)
        memoryCacheKey(key)
        diskCacheKey(key)
    }
}

fun ImageRequest.Builder.justDownload() = apply {
    memoryCachePolicy(CachePolicy.DISABLED)
    decoderFactory(BlackholeDecoder.Factory())
}

@Composable
inline fun <T : Any> PrefetchAround(data: LazyPagingItems<T>, index: Int, distance: Int, crossinline f: (T) -> ImageRequest) {
    data.peek((index - distance).coerceAtLeast(0))?.let { fetchBefore ->
        LaunchedEffect(fetchBefore) {
            f(fetchBefore).execute()
        }
    }
    data.peek((index + distance).coerceAtMost(data.itemCount - 1))?.let { fetchAhead ->
        LaunchedEffect(fetchAhead) {
            f(fetchAhead).execute()
        }
    }
}
