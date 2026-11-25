package com.hippo.ehviewer.coil

import coil3.Extras
import coil3.getExtra
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.isFile
import com.ehviewer.core.files.sendTo
import com.ehviewer.core.files.toUri
import com.hippo.ehviewer.EhApplication.Companion.thumbCache
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.client.getThumbKey
import com.hippo.ehviewer.download.downloadDir
import com.hippo.ehviewer.download.isLocal

private val downloadInfoKey = Extras.Key<DownloadInfo?>(default = null)

fun ImageRequest.Builder.downloadInfo(info: DownloadInfo) = apply {
    extras[downloadInfoKey] = info
}

val ImageRequest.downloadInfo: DownloadInfo?
    get() = getExtra(downloadInfoKey)

object DownloadThumbInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val info = chain.request.downloadInfo
        if (info != null && !info.dirname.isNullOrBlank()) {
            val isLocalImport = info.isLocal
            // 本地导入条目保留原目录图片，不改写远程 thumbKey 也不删除原目录文件。
            val thumbKey = if (isLocalImport) {
                info.thumbKey ?: return chain.proceed()
            } else {
                val remoteKey = (chain.request.data as? String)?.let(::getThumbKey)?.takeIf { '/' in it }
                    ?: info.thumbKey
                    ?: return chain.proceed()
                if ('/' in remoteKey && info.thumbKey != remoteKey) {
                    info.thumbKey = remoteKey
                    EhDB.putGalleryInfo(info.galleryInfo)
                }
                remoteKey
            }
            val dir = info.downloadDir ?: return chain.proceed()
            val format = thumbKey.substringAfterLast('.', "")
            if (format.isBlank()) return chain.proceed()
            // Local imports point thumbKey directly at their first image.
            val direct = dir / thumbKey
            val thumb = if (direct.isFile) direct else dir / "thumb.$format"
            val v1Thumb = dir / "thumb.jpg"
            if (thumb.isFile) {
                val new = chain.request.newBuilder().data(thumb.toUri()).build()
                val result = chain.withRequest(new).proceed()
                if (result is SuccessResult) {
                    if (!isLocalImport && thumb != direct && thumb != v1Thumb) v1Thumb.delete()
                    return result
                }
            }
            val result = chain.proceed()
            if (result is SuccessResult && dir.isDirectory && !isLocalImport) {
                // Accessing the recreated file immediately after deleting it throws
                // FileNotFoundException, so we just overwrite the existing file.
                val key = chain.request.diskCacheKey ?: chain.request.memoryCacheKey ?: return result
                thumbCache.read(key) {
                    data sendTo thumb
                }
                if (thumb != v1Thumb) v1Thumb.delete()
            }
            return result
        }
        return chain.proceed()
    }
}
