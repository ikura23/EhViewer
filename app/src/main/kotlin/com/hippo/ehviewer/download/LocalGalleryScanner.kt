package com.hippo.ehviewer.download

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.files.find
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.isFile
import com.ehviewer.core.files.list
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.model.BaseGalleryInfo
import com.hippo.ehviewer.client.parser.GalleryDetailUrlParser
import com.hippo.ehviewer.gallery.LocalFolderPageLoader
import com.hippo.ehviewer.spider.COMIC_INFO_FILE
import com.hippo.ehviewer.spider.SpiderQueen.Companion.SPIDER_INFO_FILENAME
import com.hippo.ehviewer.spider.readComicInfo
import com.hippo.ehviewer.spider.readCompatFromPath
import com.hippo.ehviewer.spider.toSimpleTags
import com.hippo.ehviewer.util.AppConfig
import java.io.File
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath

/**
 * 本地画廊与压缩包扫描导入器，支持免复制直接挂载设备磁盘上的既有画廊目录或 `.cbz`/`.zip` 压缩包。
 *
 * - 对于应用外部目录：保留绝对路径作为 `dirname` 并标记“本地”标签，避免移动或删除条目时误删磁盘原文件；
 * - 对于用户当前配置的自定义下载目录（含 Android SAF `content://` 文档树）及默认下载目录：
 *   自动识别归属并还原相对 `dirname`，不打“本地”标签，同时纠偏此前因重设下载目录而被误标为“本地”的条目。
 */
object LocalGalleryScanner {
    private const val LOCAL_LABEL = "本地"
    private val scanMutex = Mutex()

    fun hasAllFilesAccess(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun openAllFilesAccessSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    data class Result(
        val imported: Int,
        val scannedDirectories: Int,
        val roots: Int,
    )

    fun resolveTreePath(uri: Uri): Path? = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val volume = documentId.substringBefore(':', "")
        val relative = documentId.substringAfter(':', "")
        if (volume.isBlank()) return@runCatching null
        val base = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage/$volume")
        }
        File(base, relative).toOkioPath().takeIf { it.isDirectory }
    }.getOrNull()

    suspend fun scan(extraRoots: List<Path> = emptyList()): Result = scanMutex.withLock {
        val storage = Environment.getExternalStorageDirectory().toOkioPath()
        val configuredRoot = resolveConfiguredDownloadRoot(storage.toString())
        val appDownloadRoots = resolveAppDownloadRoots(configuredRoot)
        val rawRoots = if (extraRoots.isNotEmpty()) {
            extraRoots
        } else {
            discoverRoots(storage, configuredRoot)
        }
        val roots = deduplicateRoots(rawRoots)
        var imported = 0
        var scanned = 0
        for (root in roots) {
            val pending = ArrayDeque<Pair<Path, Int>>()
            pending.addLast(root to 0)
            while (pending.isNotEmpty()) {
                val (dir, depth) = pending.removeLast()
                val entries = runCatching { dir.list() }.getOrElse { continue }
                for (entry in entries) {
                    if (entry.isDirectory) {
                        val gallery = looksLikeGallery(entry)
                        if (gallery) {
                            scanned++
                            val item = runCatching { readLocalItem(entry) }.getOrNull()
                            if (item != null) {
                                importItem(item, entry, isArchive = false, configuredRoot, appDownloadRoots)?.let {
                                    if (it) imported++
                                }
                                continue
                            }
                        }
                        if (depth < MAX_SCAN_DEPTH) {
                            pending.addLast(entry to depth + 1)
                        }
                        continue
                    }

                    if (!isArchiveFile(entry)) continue
                    if (!isReadableArchive(entry)) continue
                    scanned++
                    val item = runCatching { readArchiveItem(entry) }.getOrNull() ?: continue
                    if (importItem(item, entry, isArchive = true, configuredRoot, appDownloadRoots) == true) {
                        imported++
                    }
                }
            }
        }
        Result(imported, scanned, roots.size)
    }

    private fun canonicalPath(path: Path): String = canonicalPath(path.toString())

    private fun canonicalPath(raw: String): String = runCatching {
        File(raw).canonicalPath
    }.getOrElse { raw }.trimEnd('/').ifEmpty { "/" }

    private fun resolveConfiguredDownloadRoot(externalStorageRoot: String): String? {
        val raw = runCatching { downloadLocation.toString() }.getOrNull() ?: return null
        val resolved = resolveDownloadLocationPath(raw, externalStorageRoot) ?: return null
        return canonicalPath(resolved)
    }

    private fun resolveAppDownloadRoots(configuredRoot: String?): List<String> = buildList {
        configuredRoot?.let(::add)
        AppConfig.defaultDownloadDir?.let { add(canonicalPath(it.absolutePath)) }
    }.distinct()

    /** 去除存在父子包含关系的重复扫描根目录，避免同一目录被多次遍历。 */
    private fun deduplicateRoots(raw: List<Path>): List<Path> {
        val pairs = raw.map { it to canonicalPath(it) }.distinctBy { it.second }.sortedBy { it.second.length }
        val kept = mutableListOf<Pair<Path, String>>()
        for (pair in pairs) {
            if (kept.none { (_, parent) -> pair.second == parent || pair.second.startsWith("$parent/") }) {
                kept += pair
            }
        }
        return kept.map { it.first }
    }

    private suspend fun importItem(
        item: LocalItem,
        path: Path,
        isArchive: Boolean,
        configuredRoot: String?,
        appDownloadRoots: List<String>,
    ): Boolean? {
        if (isArchive) {
            runCatching { ensureArchiveThumb(path, item.gid) }
        }
        val target = resolveImportTarget(
            entryCanonicalPath = canonicalPath(path),
            configuredDownloadRoot = configuredRoot,
            appDownloadRoots = appDownloadRoots,
            isArchive = isArchive,
        )
        val existing = DownloadManager.getDownloadInfo(item.gid)
        if (existing != null) {
            // 正在下载/排队/暂停中的条目不能被本地扫描改写，否则会把未完成的
            // 目录当成"已完成"并覆盖真实页数，导致下载永久停在中途。
            if (existing.state != DownloadInfo.STATE_FINISH) return false
            runCatching {
                if (target.isInsideAppDownloadDir) {
                    DownloadManager.normalizeAppDownloadEntry(
                        gid = item.gid,
                        dirname = target.dirname,
                        thumbKey = item.info.thumbKey,
                        pages = item.pages,
                        title = item.info.title,
                    )
                } else {
                    DownloadManager.attachLocalDirname(
                        item.gid,
                        target.dirname,
                        item.info.thumbKey,
                        item.pages,
                        item.info.title,
                    )
                }
            }
            return false
        }
        return runCatching {
            DownloadManager.importLocalDownload(item.info, target.dirname, item.pages, target.label)
        }.getOrDefault(false)
    }

    private fun looksLikeGallery(dir: Path): Boolean = runCatching {
        dir.list().any {
            it.name == COMIC_INFO_FILE ||
                it.name == SPIDER_INFO_FILENAME ||
                it.name.lowercase() != "thumb.webp" &&
                it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
        }
    }.getOrDefault(false)

    private fun discoverRoots(storage: Path, configuredRoot: String? = null): List<Path> {
        val roots = linkedSetOf<Path>()
        fun add(path: Path) {
            if (path.isDirectory) roots.add(path)
        }
        configuredRoot?.let { add(it.toPath()) }

        // Scan all of Download recursively so Chinese-named subfolders are included.
        add(storage / "Download")
        add(storage / "download")
        add(storage / "EhViewer" / "download")
        add(storage / "EhViewer" / "Download")
        add(storage / "EhViewerCN" / "download")
        add(storage / "Download" / "EhViewer")
        add(storage / "Download" / "EhViewer" / "download")
        add(storage / "Download" / "Ehviewer")
        add(storage / "Download" / "Ehviewer" / "download")

        // Some forks put the gallery folder directly under Download/<fork>/download.
        listOf(storage / "Download", storage / "download").forEach { download ->
            runCatching { download.list() }.getOrDefault(emptyList()).forEach { top ->
                if (!top.isDirectory) return@forEach
                val name = top.name.lowercase()
                if (name.contains("ehviewer") || name.contains("ehentai") || name.contains("eh-viewer")) {
                    add(top / "download")
                    add(top)
                }
            }
        }

        // /sdcard/* where the top-level folder is named like an EhViewer fork.
        runCatching { storage.list() }.getOrDefault(emptyList()).forEach { top ->
            if (!top.isDirectory) return@forEach
            val name = top.name.lowercase()
            if (name.contains("ehviewer") || name.contains("ehentai") || name.contains("eh-viewer")) {
                add(top / "download")
                add(top / "Download")
                add(top)
            }
        }

        // /sdcard/Android/data/<package>/files/download for every installed EhViewer fork.
        val dataRoot = storage / "Android" / "data"
        runCatching { dataRoot.list() }.getOrDefault(emptyList()).forEach { pkg ->
            if (!pkg.isDirectory) return@forEach
            val name = pkg.name.lowercase()
            if (!name.contains("ehviewer") && !name.contains("ehentai") && !name.contains("eh-viewer")) {
                return@forEach
            }
            add(pkg / "files" / "download")
            add(pkg / "files" / "Download")
            add(pkg / "files" / "EhViewer" / "download")
            add(pkg / "files" / "EhViewer" / "Download")
            add(pkg / "files" / "EhViewer")
            add(pkg / "download")
            add(pkg / "Download")
        }

        return roots.toList()
    }

    private fun ensureArchiveThumb(file: Path, gid: Long) {
        val cachePrefix = archiveThumbCachePrefix(file, gid)
        val cacheDir = AppConfig.localThumbDir.toFile()
        val cached = cacheDir.listFiles()?.firstOrNull {
            it.nameWithoutExtension == cachePrefix && it.isFile && it.length() > 0
        }
        if (cached != null && (
                cached.extension.lowercase() in setOf("gif", "webp") ||
                    BitmapFactory.decodeFile(cached.absolutePath)?.let { bitmap ->
                        bitmap.recycle()
                        true
                    } == true
                )
        ) {
            return
        }
        cached?.delete()
        File(archiveThumbPath(gid).toString()).takeIf { it.isFile }?.delete()
        writeArchiveThumb(file, gid)
    }

    private fun archiveThumbCachePrefix(file: Path, gid: Long) = "$gid-${file.toString().hashCode().toUInt().toString(16)}"

    private fun isArchiveImageEntry(entryName: String): Boolean {
        if (entryName.endsWith('/')) return false
        val normalized = entryName.replace('\\', '/')
        if (normalized.startsWith("__MACOSX/") || normalized.contains("/__MACOSX/")) return false
        val fileName = normalized.substringAfterLast('/')
        if (fileName.startsWith("._") || fileName.equals("thumb.webp", ignoreCase = true)) return false
        return fileName.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
    }

    private fun decodeDownsampledBitmap(bytes: ByteArray, targetWidth: Int = 320): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= targetWidth) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun writeArchiveThumb(file: Path, gid: Long) {
        if (file.name.substringAfterLast('.', "").lowercase() !in ARCHIVE_EXTENSIONS) return
        val cachePrefix = archiveThumbCachePrefix(file, gid)
        ZipFile(file.toString()).use { zip ->
            val sortedEntries = zip.entries().toList()
                .filter { !it.isDirectory && isArchiveImageEntry(it.name) }
                .sortedWith { a, b -> LocalFolderPageLoader.NATURAL_NAME_COMPARATOR.compare(a.name, b.name) }
            for (entry in sortedEntries) {
                if (entry.size > MAX_THUMB_SOURCE_BYTES) continue
                val entryExtension = entry.name.substringAfterLast('.', "").lowercase()
                val bytes = runCatching { zip.getInputStream(entry).use { it.readBytes() } }.getOrNull() ?: continue
                if (entryExtension == "gif" || entryExtension == "webp") {
                    val animated = File(AppConfig.localThumbDir.toString(), "$cachePrefix.$entryExtension")
                    runCatching { animated.writeBytes(bytes) }.onSuccess { return }
                }
                val bitmap = decodeDownsampledBitmap(bytes, 320) ?: continue
                if (bitmap.width <= 0 || bitmap.height <= 0) {
                    bitmap.recycle()
                    continue
                }
                val width = 320
                val height = (bitmap.height.toFloat() * width / bitmap.width).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                val archiveThumb = File(archiveThumbPath(gid).toString())
                val cachedThumb = File(AppConfig.localThumbDir.toString(), "$cachePrefix.jpg")
                val written = runCatching {
                    cachedThumb.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                    archiveThumb.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                }.isSuccess
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
                if (written) return
            }
        }
    }

    private fun isArchiveFile(path: Path): Boolean = path.name.substringAfterLast('.', "").lowercase() in ARCHIVE_EXTENSIONS

    /** Only import an archive that actually contains at least one image page. */
    private fun isReadableArchive(path: Path): Boolean = countArchivePages(path) > 0

    /** Count image entries without extracting or copying the archive. */
    private fun countArchivePages(path: Path): Int = runCatching {
        ZipFile(path.toString()).use { zip ->
            val entries = zip.entries()
            var count = 0
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().name
                if (isArchiveImageEntry(name)) {
                    count++
                }
            }
            count
        }
    }.getOrDefault(0)

    /**
     * 为非 E-Hentai 元数据归档生成稳定的 64 位负数 GID（基于规范化完整路径 SHA-256），
     * 彻底隔离真实 E-Hentai 正整数 GID 空间，避免同名/年份前缀归档互相碰撞覆盖。
     */
    internal fun stableLocalArchiveGid(canonicalFilePath: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalFilePath.toByteArray(Charsets.UTF_8))
        val positive = ByteBuffer.wrap(digest, 0, 8).long and Long.MAX_VALUE
        return -positive - 1L
    }

    /**
     * Import a .cbz/.zip without extracting or copying it. The archive path is stored as the
     * DownloadInfo dirname and ReaderScreen sends it to useArchivePageLoader().
     */
    private fun readArchiveItem(file: Path): LocalItem? {
        val base = file.name.substringBeforeLast('.')
        val pages = countArchivePages(file)
        if (pages <= 0) return null
        val gid = stableLocalArchiveGid(canonicalPath(file))
        val token = "local"
        val info = BaseGalleryInfo(gid, token).apply {
            title = cleanTitle(base)
            this.pages = pages
            simpleTags = emptyList()
        }
        runCatching { writeArchiveThumb(file, gid) }
        return LocalItem(info, gid, pages)
    }

    private fun readLocalItem(dir: Path): LocalItem? {
        val spider = dir.find(SPIDER_INFO_FILENAME)?.let { readCompatFromPath(it) }
        val comic = dir.find(COMIC_INFO_FILE)?.let { readComicInfo(it) }
        val parsed = comic?.web?.let { GalleryDetailUrlParser.parse(it) }

        // Older EhViewer forks often only have a "gid-token" or "gid_token" folder name.
        val folderName = dir.name
        val folderGid = folderName.substringBefore('-').toLongOrNull()
            ?: folderName.substringBefore('_').toLongOrNull()
        val folderToken = folderName.substringAfter('-', "")
            .ifBlank { folderName.substringAfter('_', "") }
        val gid = spider?.gid ?: parsed?.gid ?: folderGid ?: return null
        val token = spider?.token ?: parsed?.token ?: folderToken.ifBlank { null } ?: return null

        // 没有实际图片的空目录绝不能导入为已完成；有声明页数时必须满足 actualPages >= declaredPages，
        // 避免把未下完或刚建目录的 0/531、9/531 下载目录误判为完整本地图集。
        val actualPages = countPages(dir)
        if (actualPages <= 0) return null
        val declaredPages = comic?.pageCount?.takeIf { it > 0 }
            ?: spider?.pages?.takeIf { it > 0 }
        if (declaredPages != null && actualPages < declaredPages) return null
        val pages = actualPages

        val info = BaseGalleryInfo(gid, token).apply {
            title = cleanTitle(comic?.series?.takeIf { it.isNotBlank() } ?: folderName)
            this.pages = pages
            simpleTags = comic?.let { it.toSimpleTags() } ?: emptyList()
        }

        // 只读扫描外部目录，不覆写磁盘上的 .ehviewer 文件。
        // 使用自然排序后的第一张真实图片生成列表封面缓存。
        val firstImage = LocalFolderPageLoader.imageFiles(dir).firstOrNull()
        if (firstImage != null) {
            info.thumbKey = firstImage.name
            writeLocalThumb(firstImage, gid)
        }
        return LocalItem(info, gid, pages)
    }

    private fun cleanTitle(value: String): String = value.replace(Regex("^\\s*\\d+\\s*[-_]\\s*"), "").trim().ifBlank { value }

    private fun writeLocalThumb(file: Path, gid: Long) {
        runCatching {
            val target = File(AppConfig.localThumbDir.toString(), "$gid.jpg")
            if (target.isFile) return
            val raw = File(file.toString())
            if (!raw.isFile || raw.length() > MAX_THUMB_SOURCE_BYTES) return
            val bytes = raw.readBytes()
            val bitmap = decodeDownsampledBitmap(bytes, 320) ?: return
            val width = 320
            val height = (bitmap.height.toFloat() * width / bitmap.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, width, height, true).let { scaled ->
                target.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                if (scaled !== bitmap) scaled.recycle()
            }
            bitmap.recycle()
        }
    }

    private fun countPages(dir: Path): Int = runCatching {
        dir.list().count {
            it.isFile &&
                it.name.lowercase() != "thumb.webp" &&
                it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
        }
    }.getOrDefault(0)

    private data class LocalItem(
        val info: BaseGalleryInfo,
        val gid: Long,
        val pages: Int,
    )

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif")
    private val ARCHIVE_EXTENSIONS = setOf("cbz", "zip")
    private const val MAX_SCAN_DEPTH = 5
    private const val MAX_THUMB_SOURCE_BYTES = 32L * 1024 * 1024
}

internal data class ImportTarget(
    val dirname: String,
    val label: String?,
    val isInsideAppDownloadDir: Boolean,
)

/**
 * 将用户设置的 `downloadLocation`（绝对路径或 Android SAF `content://` 文档树 URI）解析为磁盘真实路径，
 * 以便判断扫描到的画廊是否属于用户当前配置的下载目录。
 */
internal fun resolveDownloadLocationPath(
    rawLocation: String,
    externalStorageRoot: String,
): String? {
    val cleanRaw = rawLocation.trim()
    if (cleanRaw.isEmpty()) return null
    if (cleanRaw.startsWith("/")) {
        return cleanRaw.trimEnd('/').ifEmpty { "/" }
    }
    if (!cleanRaw.startsWith("content:")) return null

    // 只解析外部存储提供方（com.android.externalstorage.documents）
    if (!cleanRaw.contains("com.android.externalstorage.documents", ignoreCase = true)) {
        return null
    }
    val withoutQuery = cleanRaw.substringBefore('?').substringBefore('#')
    val encodedDocId = when {
        "/document/" in withoutQuery -> withoutQuery.substringAfterLast("/document/")
        "/tree/" in withoutQuery -> withoutQuery.substringAfterLast("/tree/")
        else -> return null
    }.takeIf { it.isNotBlank() } ?: return null

    val decodedDocId = runCatching {
        URLDecoder.decode(encodedDocId.replace("+", "%2B"), "UTF-8")
    }.getOrNull() ?: return null

    val volume = decodedDocId.substringBefore(':', "")
    if (volume.isBlank()) return null
    val relative = decodedDocId.substringAfter(':', "").trim('/')
    val base = if (volume.equals("primary", ignoreCase = true)) {
        externalStorageRoot.trimEnd('/')
    } else {
        "/storage/$volume"
    }
    return if (relative.isEmpty()) base else "$base/$relative"
}

/**
 * 根据条目路径与当前应用下载目录判定导入目标：
 * - 若位于当前下载目录内：不打“本地”标签；一级子目录还原为相对 `dirname`，避免与外部只读导入混淆；
 * - 若位于外部目录：保留绝对路径并标记“本地”标签以保护磁盘原文件。
 */
internal fun resolveImportTarget(
    entryCanonicalPath: String,
    configuredDownloadRoot: String?,
    appDownloadRoots: List<String>,
    isArchive: Boolean,
): ImportTarget {
    val normalizedEntry = entryCanonicalPath.trimEnd('/').ifEmpty { "/" }
    val normalizedConfigured = configuredDownloadRoot?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    val normalizedRoots = appDownloadRoots.map { it.trimEnd('/') }.filter { it.isNotEmpty() }

    val insideAppDir = normalizedRoots.any { root ->
        normalizedEntry == root || normalizedEntry.startsWith("$root/")
    }
    if (!insideAppDir) {
        return ImportTarget(
            dirname = normalizedEntry,
            label = "本地",
            isInsideAppDownloadDir = false,
        )
    }

    val directRelative = if (!isArchive && normalizedConfigured != null && normalizedEntry.startsWith("$normalizedConfigured/")) {
        normalizedEntry.removePrefix("$normalizedConfigured/").takeIf { it.isNotEmpty() && '/' !in it }
    } else {
        null
    }
    return ImportTarget(
        dirname = directRelative ?: normalizedEntry,
        label = null,
        isInsideAppDownloadDir = true,
    )
}
