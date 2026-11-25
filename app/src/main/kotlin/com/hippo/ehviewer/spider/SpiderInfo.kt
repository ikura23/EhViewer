package com.hippo.ehviewer.spider

import com.ehviewer.core.files.read
import com.ehviewer.core.files.write
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.coil.edit
import com.hippo.ehviewer.coil.read
import com.hippo.ehviewer.ktbuilder.diskCache
import com.hippo.ehviewer.legacy.readLegacySpiderInfo
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import moe.tarsin.coroutines.runSuspendCatching
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

@Serializable
class SpiderInfo(
    val gid: Long,

    val token: String,

    var pages: Int,

    val pTokenMap: MutableMap<Int, String> = hashMapOf(),

    var previewPerPage: Int = -1,
)

/**
 * 协调校正 `.ehviewer` 缓存中的总页数。
 * 当本地 `.ehviewer` 因中途下载或旧版本写入导致页数偏小（如 39 页），
 * 而画廊元数据、已获取的 `pTokenMap` 下标或本地已有图片页号更大时，自动扩大至最大值。
 *
 * @return 若发生页数扩大修正返回 `true`，否则返回 `false`
 */
fun reconcileSpiderInfoPages(
    spiderInfo: SpiderInfo,
    galleryPages: Int,
    localMaxPage: Int = 0,
): Boolean {
    val pTokenMaxPage = (spiderInfo.pTokenMap.keys.maxOrNull() ?: -1) + 1
    val resolved = maxOf(spiderInfo.pages, galleryPages, pTokenMaxPage, localMaxPage)
    if (resolved > spiderInfo.pages) {
        spiderInfo.pages = resolved
        return true
    }
    return false
}

private val cbor = Cbor {
    ignoreUnknownKeys = true
}

fun SpiderInfo.write(file: Path) = file.write { write(cbor.encodeToByteArray(contextOf<SpiderInfo>())) }

fun SpiderInfo.saveToCache() {
    runSuspendCatching {
        spiderInfoCache.edit(gid.toString()) {
            write(data)
        }
    }.onFailure {
        logcat(it)
    }
}

private val spiderInfoCache by lazy {
    diskCache {
        directory(appCtx.cacheDir.toOkioPath() / "spider_info_v2_1")
        maxSizeBytes(20 * 1024 * 1024)
    }
}

fun readFromCache(gid: Long): SpiderInfo? = spiderInfoCache.read(gid.toString()) {
    runCatching {
        data.read { cbor.decodeFromByteArray<SpiderInfo>(readByteArray()) }
    }.onFailure {
        logcat(it)
    }.getOrNull()
}

fun readCompatFromPath(file: Path): SpiderInfo? = runCatching {
    file.read { cbor.decodeFromByteArray<SpiderInfo>(readByteArray()) }
}.getOrNull() ?: runCatching {
    file.readLegacySpiderInfo()
}.getOrNull()
