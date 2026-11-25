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
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path
import okio.Path.Companion.toOkioPath

/**
 * Imports galleries downloaded by other EhViewer builds without copying their files.
 *
 * The imported DownloadInfo keeps an absolute path in its dirname. All reader/download path
 * helpers understand absolute paths, so the original gallery directory is used in place.
 *
 * Roots are discovered dynamically because EhViewer forks use different package names and
 * different download folder names.
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
        val excluded = excludedAppDownloadRoots()
        val rawRoots = if (extraRoots.isNotEmpty()) extraRoots else discoverRoots(storage)
        val roots = deduplicateRoots(rawRoots).filterNot { isUnderAny(it, excluded) }
        var imported = 0
        var scanned = 0
        for (root in roots) {
            val pending = ArrayDeque<Pair<Path, Int>>()
            pending.addLast(root to 0)
            while (pending.isNotEmpty()) {
                val (dir, depth) = pending.removeLast()
                if (isUnderAny(dir, excluded)) continue
                val entries = runCatching { dir.list() }.getOrElse { continue }
                for (entry in entries) {
                    if (entry.isDirectory) {
                        if (isUnderAny(entry, excluded)) continue
                        val gallery = looksLikeGallery(entry)
                        if (gallery) {
                            scanned++
                            val item = runCatching { readLocalItem(entry) }.getOrNull()
                            if (item != null) {
                                importItem(item, entry, isArchive = false)?.let { if (it) imported++ }
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
                    if (importItem(item, entry, isArchive = true) == true) imported++
                }
            }
        }
        Result(imported, scanned, roots.size)
    }

    private fun canonicalPath(path: Path): String = runCatching {
        File(path.toString()).canonicalPath
    }.getOrElse { path.toString() }.trimEnd('/')

    private fun excludedAppDownloadRoots(): List<String> = buildList {
        runCatching { downloadLocation }.getOrNull()
            ?.takeIf { it.toString().startsWith("/") }
            ?.let { add(canonicalPath(it)) }
        AppConfig.defaultDownloadDir?.toOkioPath()?.let { add(canonicalPath(it)) }
    }.distinct()

    private fun isUnderAny(path: Path, excluded: List<String>): Boolean {
        if (excluded.isEmpty()) return false
        val target = canonicalPath(path)
        return excluded.any { root -> target == root || target.startsWith("$root/") }
    }

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

    private suspend fun importItem(item: LocalItem, path: Path, isArchive: Boolean): Boolean? {
        if (isArchive) {
            runCatching { ensureArchiveThumb(path, item.gid) }
        }
        val existing = DownloadManager.getDownloadInfo(item.gid)
        if (existing != null) {
            // 正在下载/排队/暂停中的条目不能被本地扫描改写，否则会把未完成的
            // 目录当成"已完成"并覆盖真实页数，导致下载永久停在中途。
            if (existing.state != DownloadInfo.STATE_FINISH) return false
            runCatching {
                DownloadManager.attachLocalDirname(
                    item.gid,
                    path.toString(),
                    item.info.thumbKey,
                    item.pages,
                    item.info.title,
                )
            }
            return false
        }
        return runCatching {
            DownloadManager.importLocalDownload(item.info, path.toString(), item.pages, LOCAL_LABEL)
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

    private fun discoverRoots(storage: Path): List<Path> {
        val roots = linkedSetOf<Path>()
        fun add(path: Path) {
            if (path.isDirectory) roots.add(path)
        }

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
