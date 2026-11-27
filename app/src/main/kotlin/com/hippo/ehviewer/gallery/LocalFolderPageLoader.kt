package com.hippo.ehviewer.gallery

import com.ehviewer.core.files.isFile
import com.ehviewer.core.files.list
import com.ehviewer.core.files.sendTo
import com.ehviewer.core.model.GalleryInfo
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.spider.resolveDownloadDir
import kotlinx.coroutines.CoroutineScope
import okio.Path

/**
 * Reads an imported local gallery directly from its image files.
 *
 * This deliberately bypasses SpiderQueen/pToken and any stale .ehviewer page count. A folder
 * imported from another EhViewer fork may contain 1102 images while its metadata still says
 * three pages; listing the folder is the only reliable source of truth.
 */
class LocalFolderPageLoader(
    scope: CoroutineScope,
    info: GalleryInfo,
    startPage: Int,
    private val files: List<Path>,
) : PageLoader(scope, info, startPage, files.size) {
    override val title: String = EhUtils.getSuitableTitle(info)

    override fun getImageExtension(index: Int): String = files[index].name.substringAfterLast('.', "")

    override fun openSource(index: Int): ImageSource {
        val file = files[index]
        return object : PathSource {
            override val source = file
            override val type = getImageExtension(index)
            override fun close() = Unit
        }
    }

    override fun openCachedPreviewSource(index: Int): ImageSource? =
        if (index in files.indices) openSource(index) else null

    override fun prefetchPages(pages: List<Int>, bounds: IntRange) = Unit

    override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) {
        notifySourceReady(index)
    }

    override fun save(index: Int, file: Path): Boolean = runCatching {
        files[index] sendTo file
        true
    }.getOrDefault(false)

    companion object {
        fun imageFiles(dir: Path): List<Path> = runCatching {
            dir.list()
                .filter {
                    it.isFile &&
                        it.name.lowercase() != "thumb.webp" &&
                        it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
                }
                .sortedWith(NATURAL_PATH_COMPARATOR)
        }.getOrDefault(emptyList())

        /** 自然排序比较器，使 1.jpg / 2.jpg / 10.jpg 按数值递增排列而非字典序。 */
        val NATURAL_NAME_COMPARATOR: Comparator<String> = Comparator { a, b ->
            var ia = 0
            var ib = 0
            val la = a.length
            val lb = b.length
            while (ia < la && ib < lb) {
                val ca = a[ia]
                val cb = b[ib]
                if (ca.isDigit() && cb.isDigit()) {
                    var za = ia
                    while (za < la && a[za] == '0') za++
                    var ea = za
                    while (ea < la && a[ea].isDigit()) ea++
                    var zb = ib
                    while (zb < lb && b[zb] == '0') zb++
                    var eb = zb
                    while (eb < lb && b[eb].isDigit()) eb++
                    val lenCmp = (ea - za).compareTo(eb - zb)
                    if (lenCmp != 0) return@Comparator lenCmp
                    for (k in 0 until (ea - za)) {
                        val dCmp = a[za + k].compareTo(b[zb + k])
                        if (dCmp != 0) return@Comparator dCmp
                    }
                    // 数值相等时按原始位数（前导零数量）稳定排序
                    val rawLenCmp = (ea - ia).compareTo(eb - ib)
                    if (rawLenCmp != 0) return@Comparator rawLenCmp
                    ia = ea
                    ib = eb
                } else {
                    val cCmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                    if (cCmp != 0) return@Comparator cCmp
                    ia++
                    ib++
                }
            }
            la.compareTo(lb)
        }

        private val NATURAL_PATH_COMPARATOR: Comparator<Path> = Comparator { a, b ->
            NATURAL_NAME_COMPARATOR.compare(a.name, b.name)
        }

        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif")
    }
}

fun resolveLocalFolder(dirname: String): Path = resolveDownloadDir(dirname)
