package com.hippo.ehviewer.download

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.Settings
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.find
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.isFile
import com.ehviewer.core.files.list
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.parser.GalleryDetailUrlParser
import com.hippo.ehviewer.gallery.LocalFolderPageLoader
import com.hippo.ehviewer.spider.COMIC_INFO_FILE
import com.hippo.ehviewer.spider.ComicInfo
import com.hippo.ehviewer.spider.SpiderQueen.Companion.SPIDER_INFO_FILENAME
import com.hippo.ehviewer.spider.readComicInfo
import com.hippo.ehviewer.spider.readCompatFromPath
import com.hippo.ehviewer.spider.toSimpleTags
import com.hippo.ehviewer.util.AppConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.tarsin.coroutines.runSuspendCatching
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath

/**
 * 扫描任务代际门控：支持取消后立即重扫且不让晚到的旧任务清理逻辑误改新一代运行状态。
 */
internal class ScanRunGate {
    private val lock = Any()
    private var running = false
    private var generation = 0L
    private var activeJob: Job? = null
    private var pendingCancelledJob: Job? = null

    fun tryStart(launchBlock: (generation: Long, previousJob: Job?) -> Job): Boolean = synchronized(lock) {
        if (running) return false
        running = true
        val gen = ++generation
        val prev = pendingCancelledJob
        pendingCancelledJob = null
        try {
            activeJob = launchBlock(gen, prev)
            true
        } catch (t: Throwable) {
            running = false
            activeJob = null
            pendingCancelledJob = prev
            throw t
        }
    }

    fun cancel(onCancelled: () -> Unit = {}): Job? = synchronized(lock) {
        generation++
        running = false
        val job = activeJob
        activeJob = null
        if (job != null) {
            pendingCancelledJob = job
            job.cancel()
        }
        onCancelled()
        job
    }

    fun finish(generation: Long): Boolean = synchronized(lock) {
        if (this.generation != generation) return false
        running = false
        activeJob = null
        true
    }
}

/**
 * 本地画廊与压缩包扫描导入器，支持免复制直接挂载设备磁盘上的既有画廊目录或 `.cbz`/`.zip` 压缩包。
 *
 * - 双通道扫描：即使未开启“所有文件访问权限”，也会自动通过 `downloadLocation`（兼容 SAF `content://` 与物理路径）
 *   扫描当前应用下载目录并归属为正常下载（不打“本地”标签）；
 * - 开启全盘权限或手动选择外部目录后，应用外部目录保留绝对路径并标记“本地”标签，避免移动或删除条目时误删磁盘原文件；
 * - 扫描后自动在后台分批联网补全分类、作者、上传者与封面信息，并保护动图封面不被压成静态缩略图。
 */
object LocalGalleryScanner {
    private const val TAG = "LocalGalleryScanner"
    private const val LOCAL_LABEL = "本地"
    private const val METADATA_COOLDOWN_MS = 10 * 60 * 1000L
    private val scanMutex = Mutex()
    private val scanScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            logcat(TAG, error)
        },
    )
    private val scanGate = ScanRunGate()
    private val failedMetadataCooldown = ConcurrentHashMap<Long, Long>()

    enum class ScanStage {
        IDLE,
        SCANNING,
        FETCHING_METADATA,
    }

    data class Result(
        val imported: Int,
        val scannedDirectories: Int,
        val roots: Int,
        val metadataUpdated: Int = 0,
    )

    data class ScanProgress(
        val running: Boolean = false,
        val stage: ScanStage = ScanStage.IDLE,
        val scanned: Int = 0,
        val total: Int = 0,
        val imported: Int = 0,
        val metadataUpdated: Int = 0,
        val completedEventId: Int = 0,
        val lastRunUserInitiated: Boolean = false,
        val lastResult: Result? = null,
    )

    private val _scanProgress = MutableStateFlow(ScanProgress())
    val scanProgress: StateFlow<ScanProgress> = _scanProgress.asStateFlow()

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

    /**
     * 在全局后台作用域启动非阻塞扫描任务（页面切换、逛榜单或阅读画廊时不会中断）。
     *
     * @return `true` 表示成功启动新任务；`false` 表示已有扫描任务正在运行。
     */
    fun launchScanInBackground(
        extraRoots: List<Path> = emptyList(),
        onlyConfiguredDownloadDir: Boolean = false,
        userInitiated: Boolean = false,
    ): Boolean = scanGate.tryStart { gen, previousJob ->
        _scanProgress.update {
            it.copy(
                running = true,
                stage = ScanStage.SCANNING,
                scanned = 0,
                total = 0,
                imported = 0,
                metadataUpdated = 0,
                lastRunUserInitiated = userInitiated,
            )
        }
        scanScope.launch {
            var scanResult: Result? = null
            var cancelled = false
            try {
                // 等待被取消的上一代扫描释放 scanMutex，再启动新一代扫描。
                previousJob?.let { runSuspendCatching { it.join() } }
                scanResult = scan(
                    extraRoots = extraRoots,
                    onlyConfiguredDownloadDir = onlyConfiguredDownloadDir,
                )
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } catch (e: Throwable) {
                logcat(TAG, e)
            } finally {
                if (scanGate.finish(gen)) {
                    _scanProgress.update { prev ->
                        prev.copy(
                            running = false,
                            stage = ScanStage.IDLE,
                            completedEventId = if (cancelled) prev.completedEventId else prev.completedEventId + 1,
                            lastResult = if (cancelled) null else scanResult,
                        )
                    }
                }
            }
        }
    }

    /**
     * 取消当前正在后台运行的本地画廊扫描任务。
     */
    fun cancelScan() {
        scanGate.cancel {
            _scanProgress.update { prev ->
                if (!prev.running) prev else prev.copy(running = false, stage = ScanStage.IDLE)
            }
        }
    }

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

    suspend fun scan(
        extraRoots: List<Path> = emptyList(),
        onlyConfiguredDownloadDir: Boolean = false,
    ): Result = scanMutex.withLock {
        val storage = Environment.getExternalStorageDirectory().toOkioPath()
        val configuredRoot = resolveConfiguredDownloadRoot(storage.toString())
        val appDownloadRoots = resolveAppDownloadRoots(configuredRoot)
        val processedPaths = hashSetOf<String>()
        var imported = 0
        var scanned = 0
        var rootCount = 0

        fun updateScanningProgress(totalHint: Int) {
            _scanProgress.update {
                if (!it.running) it else it.copy(
                    stage = ScanStage.SCANNING,
                    scanned = scanned,
                    total = maxOf(it.total, totalHint, scanned),
                    imported = imported,
                )
            }
        }

        // 通道一：扫描用户当前配置的下载目录；具备所有文件访问权限且可解析为物理路径时优先直扫物理目录
        if (extraRoots.isEmpty()) {
            val physicalConfiguredDir = configuredRoot?.takeIf { hasAllFilesAccess() }?.toPath()
                ?.takeIf { runCatching { it.isDirectory }.getOrDefault(false) }
            val currentDownloadDir = physicalConfiguredDir ?: runCatching { downloadLocation }.getOrNull()
            if (currentDownloadDir != null && runCatching { currentDownloadDir.isDirectory }.getOrDefault(false)) {
                rootCount++
                val children = runCatching { currentDownloadDir.list() }.getOrDefault(emptyList())
                val dirChildren = mutableListOf<Path>()
                val archiveChildren = mutableListOf<Path>()
                for (entry in children) {
                    if (runCatching { entry.isDirectory }.getOrDefault(false)) {
                        dirChildren += entry
                    } else if (isArchiveFile(entry)) {
                        archiveChildren += entry
                    }
                }
                updateScanningProgress(children.size)
                // 先目录后压缩包：保证同 GID 文件夹优先于压缩包入库，避免重复导入或压缩包覆盖目录。
                for (entry in dirChildren) {
                    currentCoroutineContext().ensureActive()
                    if (shouldSkipDirectoryName(entry.name)) continue
                    val subEntries = runCatching { entry.list() }.getOrNull() ?: continue
                    val subNames = subEntries.map { it.name }
                    val hasExplicitMeta = subNames.any { it == COMIC_INFO_FILE || it == SPIDER_INFO_FILENAME }
                    if (!isValidGalleryDirectoryEntries(subNames, hasExplicitMeta, allowSingleImage = false)) continue
                    scanned++
                    val item = runCatching { readLocalItem(entry, subEntries) }.getOrNull()
                    if (item != null) {
                        val forcedTarget = resolveConfiguredEntryTarget(
                            entry = entry,
                            isArchive = false,
                            configuredRoot = configuredRoot,
                            appDownloadRoots = appDownloadRoots,
                        )
                        if (importItem(item, entry, isArchive = false, configuredRoot = configuredRoot, appDownloadRoots = appDownloadRoots, overrideTarget = forcedTarget) == true) {
                            imported++
                        }
                        markProcessed(processedPaths, entry, configuredRoot)
                    }
                    updateScanningProgress(children.size)
                }
                for (entry in archiveChildren) {
                    currentCoroutineContext().ensureActive()
                    if (entry.name.startsWith("._")) continue
                    val resolvedArchive = resolvePhysicalArchivePath(entry, configuredRoot)
                    val archiveTargetPath = resolvedArchive ?: entry
                    if (!archiveTargetPath.toString().startsWith("/")) continue
                    val archiveTarget = resolveConfiguredEntryTarget(
                        entry = archiveTargetPath,
                        isArchive = true,
                        configuredRoot = configuredRoot,
                        appDownloadRoots = appDownloadRoots,
                    )
                    val handledFast = handleExistingArchiveFast(archiveTargetPath, archiveTarget)
                    if (handledFast) {
                        scanned++
                        markProcessed(processedPaths, archiveTargetPath, configuredRoot)
                        updateScanningProgress(children.size)
                        continue
                    }
                    if (!isReadableArchivePath(archiveTargetPath)) continue
                    scanned++
                    val item = runCatching { readArchiveItem(archiveTargetPath) }.getOrNull()
                    if (item != null) {
                        if (importItem(item, archiveTargetPath, isArchive = true, configuredRoot = configuredRoot, appDownloadRoots = appDownloadRoots, overrideTarget = archiveTarget) == true) {
                            imported++
                        }
                        markProcessed(processedPaths, archiveTargetPath, configuredRoot)
                    }
                    updateScanningProgress(children.size)
                }
            }
        }

        // 通道二：具备所有文件访问权限或用户显式选取外部目录时，递归扫描全盘常见画廊目录
        val scanExternalRoots = extraRoots.isNotEmpty() || (!onlyConfiguredDownloadDir && hasAllFilesAccess())
        if (scanExternalRoots) {
            val rawRoots = if (extraRoots.isNotEmpty()) {
                extraRoots
            } else {
                discoverRoots(storage, configuredRoot)
            }
            val roots = deduplicateRoots(rawRoots)
            rootCount += roots.size
            for (root in roots) {
                val rootKey = canonicalPath(root)
                val rootEntries = runCatching { root.list() }.getOrNull()
                // 若用户直接选取某个非通用下载目录的画廊文件夹作为扫描根目录，直接识别该根目录自身。
                if (rootEntries != null &&
                    !isGenericDownloadRootPath(rootKey, storage.toString()) &&
                    rootKey !in processedPaths
                ) {
                    val rootNames = rootEntries.map { it.name }
                    val hasExplicitMeta = rootNames.any { it == COMIC_INFO_FILE || it == SPIDER_INFO_FILENAME }
                    if (isValidGalleryDirectoryEntries(rootNames, hasExplicitMeta, allowSingleImage = true)) {
                        scanned++
                        val item = runCatching { readLocalItem(root, rootEntries) }.getOrNull()
                        if (item != null) {
                            if (importItem(item, root, isArchive = false, configuredRoot = configuredRoot, appDownloadRoots = appDownloadRoots) == true) {
                                imported++
                            }
                            processedPaths += rootKey
                            updateScanningProgress(scanned)
                            continue
                        }
                    }
                }
                val pending = ArrayDeque<Pair<Path, Int>>()
                pending.addLast(root to 0)
                while (pending.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val (dir, depth) = pending.removeLast()
                    val entries = runCatching { dir.list() }.getOrElse { continue }
                    val dirEntries = mutableListOf<Path>()
                    val archiveEntries = mutableListOf<Path>()
                    for (entry in entries) {
                        if (runCatching { entry.isDirectory }.getOrDefault(false)) {
                            dirEntries += entry
                        } else if (isArchiveFile(entry)) {
                            archiveEntries += entry
                        }
                    }
                    // 同目录下也优先扫目录再扫压缩包，防止压缩包抢先占位。
                    for (entry in dirEntries) {
                        currentCoroutineContext().ensureActive()
                        if (shouldSkipDirectoryName(entry.name)) continue
                        val entryKey = canonicalPath(entry)
                        val subEntries = runCatching { entry.list() }.getOrNull()
                        if (subEntries != null) {
                            val subNames = subEntries.map { it.name }
                            val hasExplicitMeta = subNames.any { it == COMIC_INFO_FILE || it == SPIDER_INFO_FILENAME }
                            if (isValidGalleryDirectoryEntries(subNames, hasExplicitMeta, allowSingleImage = false)) {
                                if (entryKey in processedPaths) continue
                                scanned++
                                val item = runCatching { readLocalItem(entry, subEntries) }.getOrNull()
                                if (item != null) {
                                    if (importItem(item, entry, isArchive = false, configuredRoot = configuredRoot, appDownloadRoots = appDownloadRoots) == true) {
                                        imported++
                                    }
                                    processedPaths += entryKey
                                    updateScanningProgress(scanned + pending.size)
                                    continue
                                }
                                updateScanningProgress(scanned + pending.size)
                            }
                        }
                        if (depth < MAX_SCAN_DEPTH) {
                            pending.addLast(entry to depth + 1)
                        }
                    }
                    for (entry in archiveEntries) {
                        currentCoroutineContext().ensureActive()
                        if (entry.name.startsWith("._")) continue
                        val entryKey = canonicalPath(entry)
                        if (entryKey in processedPaths) continue
                        if (!entry.toString().startsWith("/")) continue
                        val target = resolveImportTarget(
                            entryCanonicalPath = entryKey,
                            configuredDownloadRoot = configuredRoot,
                            appDownloadRoots = appDownloadRoots,
                            isArchive = true,
                        )
                        if (handleExistingArchiveFast(entry, target)) {
                            scanned++
                            processedPaths += entryKey
                            updateScanningProgress(scanned + pending.size)
                            continue
                        }
                        if (!isReadableArchivePath(entry)) continue
                        scanned++
                        val item = runCatching { readArchiveItem(entry) }.getOrNull() ?: continue
                        if (importItem(item, entry, isArchive = true, configuredRoot = configuredRoot, appDownloadRoots = appDownloadRoots, overrideTarget = target) == true) {
                            imported++
                        }
                        processedPaths += entryKey
                        updateScanningProgress(scanned + pending.size)
                    }
                }
            }
        }

        // 标准扫描结束后，清理历史上误导入的非画廊软件/浏览器条目及与目录重复的压缩包条目（绝不删磁盘原文件）。
        if (extraRoots.isEmpty()) {
            runSuspendCatching {
                cleanupStaleOrDuplicateLocalEntries(
                    storageRoot = storage.toString(),
                    appDownloadRoots = appDownloadRoots,
                )
            }
        }

        // 阶段二：自动联网批量补全缺少分类、作者、上传者或远程封面的画廊元数据
        val metadataUpdated = enrichMissingMetadata()
        Result(
            imported = imported,
            scannedDirectories = scanned,
            roots = rootCount,
            metadataUpdated = metadataUpdated,
        )
    }

    private fun resolvePhysicalArchivePath(entry: Path, configuredRoot: String?): Path? {
        if (entry.toString().startsWith("/")) return entry
        val root = configuredRoot ?: return null
        val candidate = File(root, entry.name)
        return candidate.takeIf { it.isFile }?.toOkioPath()
    }

    /**
     * 对已在库中的 `.cbz`/`.zip` 压缩包跳过耗时的 Zip 目录/封面重读；
     * 若已有同 GID 的目录在库则直接跳过压缩包，防止重复导入；
     * 若它位于应用下载目录内，顺手纠偏清除残留的“本地”标签。
     */
    private suspend fun handleExistingArchiveFast(entry: Path, target: ImportTarget): Boolean {
        val gid = resolveArchiveGid(entry.name, canonicalPath(entry))
        val existing = DownloadManager.getDownloadInfo(gid) ?: return false
        val existingIsDir = existing.downloadDir?.let { runCatching { it.isDirectory }.getOrDefault(false) } == true
        if (existingIsDir) return true
        if (existing.state == DownloadInfo.STATE_FINISH && target.isInsideAppDownloadDir) {
            runSuspendCatching {
                DownloadManager.normalizeAppDownloadEntry(
                    gid = gid,
                    dirname = target.dirname,
                    pages = existing.pages,
                )
            }
        }
        return true
    }

    private fun markProcessed(processedPaths: MutableSet<String>, entry: Path, configuredRoot: String?) {
        processedPaths += canonicalPath(entry)
        val raw = entry.toString()
        if (!raw.startsWith("/") && configuredRoot != null) {
            processedPaths += canonicalPath("$configuredRoot/${entry.name}")
        }
    }

    private fun resolveConfiguredEntryTarget(
        entry: Path,
        isArchive: Boolean,
        configuredRoot: String?,
        appDownloadRoots: List<String>,
    ): ImportTarget {
        val raw = entry.toString()
        if (raw.startsWith("/")) {
            return resolveImportTarget(
                entryCanonicalPath = canonicalPath(entry),
                configuredDownloadRoot = configuredRoot,
                appDownloadRoots = appDownloadRoots,
                isArchive = isArchive,
            )
        }
        if (!isArchive) {
            // SAF 模式下位于下载目录的一级画廊目录直接用相对子目录名，不打“本地”标签
            return ImportTarget(
                dirname = entry.name,
                label = null,
                isInsideAppDownloadDir = true,
            )
        }
        val physicalArchive = configuredRoot?.let { "$it/${entry.name}" }
        return ImportTarget(
            dirname = physicalArchive ?: raw,
            label = null,
            isInsideAppDownloadDir = true,
        )
    }

    private suspend fun enrichMissingMetadata(): Int {
        val now = System.currentTimeMillis()
        val candidates = DownloadManager.downloadInfoList.filter { info ->
            if (info.gid <= 0L || !isValidGalleryHexToken(info.token)) return@filter false
            val needsMetadata = info.category == 0 ||
                info.uploader.isNullOrBlank() ||
                info.thumbKey.isNullOrBlank() ||
                '/' !in info.thumbKey!!
            if (!needsMetadata) return@filter false
            val lastAttempt = failedMetadataCooldown[info.gid]
            lastAttempt == null || now - lastAttempt >= METADATA_COOLDOWN_MS
        }.map { info ->
            BaseGalleryInfo(gid = info.gid, token = info.token)
        }
        if (candidates.isEmpty()) return 0

        var processed = 0
        var updated = 0
        _scanProgress.update {
            if (!it.running) it else it.copy(
                stage = ScanStage.FETCHING_METADATA,
                scanned = 0,
                total = candidates.size,
                metadataUpdated = 0,
            )
        }

        for (batch in candidates.chunked(25)) {
            currentCoroutineContext().ensureActive()
            val attemptTime = System.currentTimeMillis()
            val response = runSuspendCatching {
                EhEngine.fillGalleryListByApi(batch, EhUrl.referer)
            }
            if (response.isSuccess) {
                val validFilled = mutableListOf<BaseGalleryInfo>()
                for (filled in batch) {
                    if (filled.category != 0 || !filled.uploader.isNullOrBlank() || !filled.simpleTags.isNullOrEmpty()) {
                        validFilled += filled
                    } else {
                        failedMetadataCooldown[filled.gid] = attemptTime
                    }
                }
                if (validFilled.isNotEmpty()) {
                    updated += DownloadManager.syncGalleryMetadataBatch(validFilled)
                }
            }
            processed += batch.size
            _scanProgress.update {
                if (!it.running) it else it.copy(
                    stage = ScanStage.FETCHING_METADATA,
                    scanned = processed,
                    total = candidates.size,
                    metadataUpdated = updated,
                )
            }
        }
        return updated
    }

    private fun canonicalPath(path: Path): String = canonicalPath(path.toString())

    private fun canonicalPath(raw: String): String {
        if (!raw.startsWith("/")) return raw
        return runCatching {
            File(raw).canonicalPath
        }.getOrElse { raw }.trimEnd('/').ifEmpty { "/" }
    }

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
        overrideTarget: ImportTarget? = null,
    ): Boolean? {
        val target = overrideTarget ?: resolveImportTarget(
            entryCanonicalPath = canonicalPath(path),
            configuredDownloadRoot = configuredRoot,
            appDownloadRoots = appDownloadRoots,
            isArchive = isArchive,
        )
        val (resolvedTotalPages, resolvedState) = resolveLocalDownloadCompletion(
            actualImages = item.pages,
            spiderPages = item.spiderPages,
            isInsideAppDownloadDir = target.isInsideAppDownloadDir,
            hasValidRemoteIdentity = item.hasValidRemoteIdentity,
        )
        val existing = DownloadManager.getDownloadInfo(item.gid)
        if (existing != null) {
            // 正在下载/排队/暂停中的条目不能被本地扫描改写，否则会把未完成的
            // 目录当成"已完成"并覆盖真实页数，导致下载永久停在中途。
            if (existing.state != DownloadInfo.STATE_FINISH) return false
            // 目录优先于压缩包：已有同 GID 文件夹时，不再用压缩包覆盖。
            val existingIsDir = existing.downloadDir?.let { runCatching { it.isDirectory }.getOrDefault(false) } == true
            if (isArchive && existingIsDir) return false
            // 应用主下载目录优先于外部历史目录：防止旧分支遗留文件夹反向覆盖当前正主。
            val existingExistsOnDisk = existing.downloadDir?.let { runCatching { it.exists() }.getOrDefault(false) } == true
            if (!target.isInsideAppDownloadDir && !existing.isLocal && existingExistsOnDisk) return false

            if (isArchive) {
                runCatching { ensureArchiveThumb(path, item.gid) }
            }
            runSuspendCatching {
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
            if (!isArchive && readUpgradeReplaceMarker(path) != null) {
                runSuspendCatching { DownloadManager.completePendingUpgradeReplacementIfFinished(item.gid) }
            }
            return false
        }
        if (isArchive) {
            runCatching { ensureArchiveThumb(path, item.gid) }
        }
        val imported = runSuspendCatching {
            DownloadManager.importLocalDownload(
                galleryInfo = item.info,
                absolutePath = target.dirname,
                pages = resolvedTotalPages,
                label = target.label,
                downloadedPages = item.pages,
                initialState = resolvedState,
            )
        }.getOrDefault(false)
        if (imported && resolvedState == DownloadInfo.STATE_FINISH && !isArchive && readUpgradeReplaceMarker(path) != null) {
            runSuspendCatching { DownloadManager.completePendingUpgradeReplacementIfFinished(item.gid) }
        }
        return imported
    }

    /**
     * 清理历史上误扫描入库的浏览器/应用杂物、已消失本地条目，以及与现有画廊目录重复的压缩包条目。
     * 仅从数据库列表移除记录，绝不删除磁盘上的任何原文件。
     */
    private suspend fun cleanupStaleOrDuplicateLocalEntries(
        storageRoot: String,
        appDownloadRoots: List<String>,
    ): Int {
        val snapshot = DownloadManager.downloadInfoList.toList()
        if (snapshot.isEmpty()) return 0

        val validDirGids = snapshot.mapNotNullTo(hashSetOf()) { info ->
            if (info.gid <= 0L) return@mapNotNullTo null
            val dir = info.downloadDir ?: return@mapNotNullTo null
            val isDir = runCatching { dir.isDirectory }.getOrDefault(false)
            if (isDir) info.gid else null
        }

        val normalizedStorage = storageRoot.trimEnd('/').ifEmpty { "/" }
        val genericDownloadPrefixes = listOf("$normalizedStorage/Download", "$normalizedStorage/download")
        val normalizedAppRoots = appDownloadRoots.map { it.trimEnd('/').ifEmpty { "/" } }

        val staleGids = mutableListOf<Long>()
        for (info in snapshot) {
            if (info.state != DownloadInfo.STATE_FINISH) continue
            val rawDir = info.downloadDir?.toString() ?: continue
            val isPhysical = rawDir.startsWith("/")

            // 1. 外部压缩包如果是用旧负 GID 导入的，且同正 GID 目录已在库中，清除重复压缩包记录
            if (info.gid < 0L && isPhysical && isArchivePathName(rawDir)) {
                val file = File(rawDir)
                if (!file.isFile) {
                    staleGids += info.gid
                    continue
                }
                val parsedGid = resolveArchiveGid(file.name, canonicalPath(rawDir))
                if (parsedGid > 0L && parsedGid in validDirGids) {
                    staleGids += info.gid
                    continue
                }
                // 旧误扫的非画廊普通压缩包（如浏览器/网盘软件 zip）
                if (!isReadableArchivePath(rawDir.toPath())) {
                    staleGids += info.gid
                    continue
                }
            }

            if (!info.isLocal || !isPhysical) continue

            val canonical = canonicalPath(rawDir)
            val diskFile = File(canonical)
            if (!diskFile.exists()) {
                staleGids += info.gid
                continue
            }

            if (info.gid < 0L && diskFile.isDirectory) {
                if (isGenericDownloadRootPath(canonical, normalizedStorage)) {
                    staleGids += info.gid
                    continue
                }
                val relativeSegments = canonical.removePrefix("$normalizedStorage/").split('/').filter { it.isNotEmpty() }
                if (relativeSegments.any(::shouldSkipDirectoryName)) {
                    staleGids += info.gid
                    continue
                }
                val inGenericDownload = genericDownloadPrefixes.any { prefix ->
                    canonical == prefix || canonical.startsWith("$prefix/")
                }
                val inAllowedAppOrEhRoot = normalizedAppRoots.any { root ->
                    canonical == root || canonical.startsWith("$root/")
                } || relativeSegments.any { seg ->
                    val low = seg.lowercase()
                    low.contains("ehviewer") || low.contains("ehentai") || low.contains("eh-viewer")
                }
                if (inGenericDownload && !inAllowedAppOrEhRoot) {
                    val hasExplicitMeta = File(diskFile, SPIDER_INFO_FILENAME).isFile || File(diskFile, COMIC_INFO_FILE).isFile
                    if (!hasExplicitMeta) {
                        staleGids += info.gid
                        continue
                    }
                }
            }
        }
        return if (staleGids.isNotEmpty()) {
            DownloadManager.removeStaleLocalEntries(staleGids)
        } else {
            0
        }
    }

    private fun isArchivePathName(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in ARCHIVE_EXTENSIONS

    private fun looksLikeGalleryFromEntries(entries: List<Path>): Boolean = entries.any {
        it.name == COMIC_INFO_FILE ||
            it.name == SPIDER_INFO_FILENAME ||
            (
                it.name.lowercase() != "thumb.webp" &&
                    it.name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
                )
    }

    private fun looksLikeGallery(dir: Path): Boolean = runCatching {
        looksLikeGalleryFromEntries(dir.list())
    }.getOrDefault(false)

    private fun discoverRoots(storage: Path, configuredRoot: String? = null): List<Path> {
        val roots = linkedSetOf<Path>()
        fun add(path: Path) {
            if (path.isDirectory) roots.add(path)
        }
        configuredRoot?.let { add(it.toPath()) }

        // 仅扫描明确的 EhViewer 系列目录，绝不整盘泛扫 /sdcard/Download 下的浏览器/聊天杂图。
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
        var cached: File? = null
        for (ext in listOf("webp", "gif", "jpg", "jpeg", "png")) {
            val candidate = File(cacheDir, "$cachePrefix.$ext")
            if (candidate.isFile && candidate.length() > 0L) {
                cached = candidate
                break
            }
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

    private fun archiveThumbCachePrefix(file: Path, gid: Long) =
        "$ARCHIVE_THUMB_CACHE_VERSION-$gid-${file.toString().hashCode().toUInt().toString(16)}"

    private fun isArchiveImageEntry(entryName: String): Boolean {
        if (entryName.endsWith('/')) return false
        val normalized = entryName.replace('\\', '/')
        if (normalized.startsWith("__MACOSX/") || normalized.contains("/__MACOSX/")) return false
        val fileName = normalized.substringAfterLast('/')
        if (fileName.startsWith("._") || fileName.equals("thumb.webp", ignoreCase = true)) return false
        return fileName.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
    }

    private fun decodeDownsampledBitmap(bytes: ByteArray, targetWidth: Int = ARCHIVE_THUMB_TARGET_WIDTH): Bitmap? {
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

    private fun openArchiveInputStream(path: Path): InputStream =
        if (path.toString().startsWith("/")) {
            File(path.toString()).inputStream()
        } else {
            ParcelFileDescriptor.AutoCloseInputStream(path.openFileDescriptor("r"))
        }

    private fun writeArchiveThumb(file: Path, gid: Long) {
        if (file.name.substringAfterLast('.', "").lowercase() !in ARCHIVE_EXTENSIONS) return
        val cachePrefix = archiveThumbCachePrefix(file, gid)
        if (file.toString().startsWith("/")) {
            ZipFile(file.toString()).use { zip ->
                val sortedEntries = zip.entries().toList()
                    .filter { !it.isDirectory && isArchiveImageEntry(it.name) }
                    .sortedWith { a, b -> LocalFolderPageLoader.NATURAL_NAME_COMPARATOR.compare(a.name, b.name) }
                for (entry in sortedEntries) {
                    if (entry.size > MAX_THUMB_SOURCE_BYTES) continue
                    val entryExtension = entry.name.substringAfterLast('.', "").lowercase()
                    val bytes = runCatching {
                        zip.getInputStream(entry).use { readStreamBytesLimited(it, MAX_THUMB_SOURCE_BYTES) }
                    }.getOrNull() ?: continue
                    if (saveArchiveEntryThumbBytes(bytes, entryExtension, gid, cachePrefix)) return
                }
            }
            return
        }
        // SAF 归档降级流式扫描首个合法图像条目
        runCatching {
            openArchiveInputStream(file).use { rawStream ->
                ZipInputStream(rawStream).use { zis ->
                    var bestName: String? = null
                    var bestBytes: ByteArray? = null
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        if (!entry.isDirectory && isArchiveImageEntry(entry.name) && entry.size <= MAX_THUMB_SOURCE_BYTES) {
                            if (bestName == null || LocalFolderPageLoader.NATURAL_NAME_COMPARATOR.compare(entry.name, bestName) < 0) {
                                val bytes = runCatching { readStreamBytesLimited(zis, MAX_THUMB_SOURCE_BYTES) }.getOrNull()
                                if (bytes != null && bytes.isNotEmpty()) {
                                    bestName = entry.name
                                    bestBytes = bytes
                                }
                            }
                        }
                        zis.closeEntry()
                    }
                    if (bestName != null && bestBytes != null) {
                        val ext = bestName.substringAfterLast('.', "").lowercase()
                        saveArchiveEntryThumbBytes(bestBytes, ext, gid, cachePrefix)
                    }
                }
            }
        }
    }

    private fun saveArchiveEntryThumbBytes(
        bytes: ByteArray,
        entryExtension: String,
        gid: Long,
        cachePrefix: String,
    ): Boolean {
        if (entryExtension == "gif" || isAnimatedImageHeader(bytes)) {
            val outExt = if (entryExtension == "webp" || entryExtension == "gif") entryExtension else "gif"
            val animated = File(AppConfig.localThumbDir.toString(), "$cachePrefix.$outExt")
            if (runCatching { animated.writeBytes(bytes) }.isSuccess) return true
        }
        val bitmap = decodeDownsampledBitmap(bytes, ARCHIVE_THUMB_TARGET_WIDTH) ?: return false
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            bitmap.recycle()
            return false
        }
        val scaled = if (bitmap.width > ARCHIVE_THUMB_TARGET_WIDTH) {
            val height = (bitmap.height.toFloat() * ARCHIVE_THUMB_TARGET_WIDTH / bitmap.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, ARCHIVE_THUMB_TARGET_WIDTH, height, true)
        } else {
            bitmap
        }
        val archiveThumb = File(archiveThumbPath(gid).toString())
        val cachedThumb = File(AppConfig.localThumbDir.toString(), "$cachePrefix.jpg")
        val written = runCatching {
            cachedThumb.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, ARCHIVE_THUMB_JPEG_QUALITY, it) }
            archiveThumb.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, ARCHIVE_THUMB_JPEG_QUALITY, it) }
        }.isSuccess
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return written
    }

    private fun isArchiveFile(path: Path): Boolean = path.name.substringAfterLast('.', "").lowercase() in ARCHIVE_EXTENSIONS

    /** 仅当压缩包内部通过画廊结构校验（排除软件/文档等普通 zip）时才允许导入。 */
    private fun isReadableArchivePath(path: Path): Boolean = countArchivePages(path) > 0

    /** 统计合格画廊压缩包内的图片数量，不解压不复制原始压缩包；不合格则返回 0。 */
    private fun countArchivePages(path: Path): Int = runCatching {
        val entryNames = if (path.toString().startsWith("/")) {
            ZipFile(path.toString()).use { zip ->
                val list = mutableListOf<String>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (!e.isDirectory) list += e.name
                }
                list
            }
        } else {
            openArchiveInputStream(path).use { rawStream ->
                ZipInputStream(rawStream).use { zis ->
                    val list = mutableListOf<String>()
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        if (!entry.isDirectory) list += entry.name
                        zis.closeEntry()
                    }
                    list
                }
            }
        }
        if (!isValidGalleryArchiveEntries(path.name, entryNames)) return@runCatching 0
        entryNames.count(::isArchiveImageEntry)
    }.getOrDefault(0)

    /**
     * 为非 E-Hentai 元数据归档生成稳定的 64 位负数 GID（基于规范化完整路径 SHA-256），
     * 彻底隔离真实 E-Hentai 正整数 GID 空间，避免同名/年份前缀归档互相碰撞覆盖。
     */
    internal fun stableLocalArchiveGid(canonicalFilePath: String): Long =
        stableNegativeGid(canonicalFilePath)

    internal fun stableLocalDirGid(canonicalDirPath: String): Long =
        stableNegativeGid("dir:$canonicalDirPath")

    private fun stableNegativeGid(key: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val positive = ByteBuffer.wrap(digest, 0, 8).long and Long.MAX_VALUE
        return -positive - 1L
    }

    /**
     * 无解压直接导入 `.cbz`/`.zip` 压缩包，完整路径存入 `dirname`，阅读时直接交由 `useArchivePageLoader`。
     */
    private fun readArchiveItem(file: Path): LocalItem? {
        val base = file.name.substringBeforeLast('.')
        val pages = countArchivePages(file)
        if (pages <= 0) return null
        val gid = resolveArchiveGid(file.name, canonicalPath(file))
        val token = "local"
        val info = BaseGalleryInfo(gid, token).apply {
            title = cleanTitle(base)
            this.pages = pages
            simpleTags = emptyList()
        }
        runCatching { writeArchiveThumb(file, gid) }
        return LocalItem(info, gid, pages)
    }

    private fun ComicInfo.toNormalizedSimpleTags(): List<String>? {
        val merged = linkedSetOf<String>()
        writer?.forEach { name ->
            val trimmed = name.trim()
            if (trimmed.isNotEmpty()) {
                merged += if (':' in trimmed) trimmed else "group:$trimmed"
            }
        }
        penciller?.forEach { name ->
            val trimmed = name.trim()
            if (trimmed.isNotEmpty()) {
                merged += if (':' in trimmed) trimmed else "artist:$trimmed"
            }
        }
        toSimpleTags()?.forEach { tag ->
            val trimmed = tag.trim()
            if (trimmed.isNotEmpty()) merged += trimmed
        }
        return merged.toList().ifEmpty { null }
    }

    private fun readLocalItem(dir: Path, prelistedEntries: List<Path>? = null): LocalItem? {
        val entries = prelistedEntries ?: runCatching { dir.list() }.getOrDefault(emptyList())
        if (entries.isEmpty()) return null

        val spider = entries.firstOrNull { it.name == SPIDER_INFO_FILENAME }?.let { readCompatFromPath(it) }
        val comic = entries.firstOrNull { it.name == COMIC_INFO_FILE }?.let { readComicInfo(it) }
        val parsed = comic?.web?.let { GalleryDetailUrlParser.parse(it) }

        // 旧版或第三方目录可能以 "gid-token"、"gid_token" 或 "gid-标题" 命名；年份等短数字前缀目录走稳定负数 GID
        val folderName = dir.name
        val gid = resolveFolderGid(
            folderName = folderName,
            spiderGid = spider?.gid,
            parsedGid = parsed?.gid,
            canonicalDirPath = canonicalPath(dir),
        )
        val rawFolderSuffix = folderName.substringAfter('-', "")
            .ifBlank { folderName.substringAfter('_', "") }
        // 仅当目录后缀为规范 10 位十六进制 token 时才采纳，防止把 "[Ka..." 等标题片段误存为假 token
        val token = spider?.token?.takeIf { it.isNotBlank() }
            ?: parsed?.token?.takeIf { it.isNotBlank() }
            ?: rawFolderSuffix.takeIf { gid > 0L && isValidGalleryHexToken(it) }
            ?: ""

        // 单次复用已列出的目录条目筛选自然排序真实图片，消除重复目录遍历与文件 stat
        val isPhysicalDir = dir.toString().startsWith("/")
        val imageEntries = entries.filter { entry ->
            val ext = entry.name.substringAfterLast('.', "").lowercase()
            if (entry.name.equals("thumb.webp", ignoreCase = true) || ext !in IMAGE_EXTENSIONS) {
                return@filter false
            }
            if (isPhysicalDir) File(entry.toString()).isFile else entry.isFile
        }.sortedWith { a, b ->
            LocalFolderPageLoader.NATURAL_NAME_COMPARATOR.compare(a.name, b.name)
        }
        val actualPages = imageEntries.size
        if (actualPages <= 0) return null
        val pages = actualPages

        val info = BaseGalleryInfo(gid, token).apply {
            title = comic?.series?.takeIf { it.isNotBlank() }?.let(::cleanTitle)
                ?: if (gid > 0L) cleanTitle(folderName) else folderName
            titleJpn = comic?.alternateSeries?.takeIf { it.isNotBlank() }
            comic?.communityRating?.toFloatOrNull()?.let { rating = it }
            comic?.languageISO?.takeIf { it.isNotBlank() }?.let { simpleLanguage = it }
            this.pages = pages
            simpleTags = comic?.toNormalizedSimpleTags() ?: emptyList()
        }

        // 只读扫描目录，不修改磁盘原有 .ehviewer 文件。
        // 仅当第一张真实图片不是动图时生成静态封面缓存；动图直接交由列表页播放原图。
        val firstImage = imageEntries.firstOrNull()
        if (firstImage != null) {
            info.thumbKey = firstImage.name
            writeLocalThumb(firstImage, gid)
        }
        return LocalItem(
            info = info,
            gid = gid,
            pages = pages,
            spiderPages = spider?.pages?.takeIf { it > 0 },
            hasValidRemoteIdentity = gid > 0L && isValidGalleryHexToken(token),
        )
    }

    private fun cleanTitle(value: String): String = value.replace(Regex("^\\s*\\d+\\s*[-_]\\s*"), "").trim().ifBlank { value }

    private fun isAnimatedImagePath(path: Path): Boolean = runCatching {
        if (path.toString().startsWith("/")) {
            isAnimatedImageFile(File(path.toString()))
        } else {
            ParcelFileDescriptor.AutoCloseInputStream(path.openFileDescriptor("r")).use { input ->
                val buf = ByteArray(4096)
                val read = input.read(buf)
                read > 0 && isAnimatedImageHeader(buf, read)
            }
        }
    }.getOrDefault(false)

    private fun readPathBytesLimited(path: Path, maxBytes: Long): ByteArray? = runCatching {
        if (path.toString().startsWith("/")) {
            val file = File(path.toString())
            if (!file.isFile || file.length() > maxBytes) return null
            file.readBytes()
        } else {
            ParcelFileDescriptor.AutoCloseInputStream(path.openFileDescriptor("r")).use { input ->
                readStreamBytesLimited(input, maxBytes)
            }
        }
    }.getOrNull()

    private fun writeLocalThumb(file: Path, gid: Long) {
        runCatching {
            val target = File(AppConfig.localThumbDir.toString(), "$gid.jpg")
            // 物理目录或动图不再生成低清 .jpg 缩略图，由列表直接交由 Coil 高清解码原图，并清理历史 320px 糊图。
            if (file.toString().startsWith("/") || isAnimatedImagePath(file)) {
                if (target.isFile) target.delete()
                return
            }
            if (target.isFile) return
            val bytes = readPathBytesLimited(file, MAX_THUMB_SOURCE_BYTES) ?: return
            val bitmap = decodeDownsampledBitmap(bytes, ARCHIVE_THUMB_TARGET_WIDTH) ?: return
            val scaled = if (bitmap.width > ARCHIVE_THUMB_TARGET_WIDTH) {
                val height = (bitmap.height.toFloat() * ARCHIVE_THUMB_TARGET_WIDTH / bitmap.width).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, ARCHIVE_THUMB_TARGET_WIDTH, height, true)
            } else {
                bitmap
            }
            target.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, ARCHIVE_THUMB_JPEG_QUALITY, it) }
            if (scaled !== bitmap) scaled.recycle()
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
        val spiderPages: Int? = null,
        val hasValidRemoteIdentity: Boolean = false,
    )

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif")
    private val ARCHIVE_EXTENSIONS = setOf("cbz", "zip")
    private const val MAX_SCAN_DEPTH = 5
    private const val MAX_THUMB_SOURCE_BYTES = 32L * 1024 * 1024
    internal const val ARCHIVE_THUMB_TARGET_WIDTH = 640
    internal const val ARCHIVE_THUMB_JPEG_QUALITY = 92
    internal const val ARCHIVE_THUMB_CACHE_VERSION = "v2"
}

private val SKIPPED_DIRECTORY_NAMES = setOf(
    "__macosx",
    "android",
    "mibrowser",
    "quarkdownloads",
    "quarkscan",
    "ucdownloads",
    "qwendownloads",
    "qq",
    "qqmusic",
    "baidunetdisk",
    "baidu",
    "netease",
    "dlmanager",
    "wechat",
    "weixin",
    "gamehub",
    "keep",
    "feimaonetdisk",
    "alipay",
    "megadownloads",
    "mega downloads",
    "duokan",
    "byerun-main",
    "com.lerist.fakelocation",
)

private val DIRECTORY_FILTER_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif")

private val NON_GALLERY_ARTIFACT_EXTENSIONS = setOf(
    "apk",
    "exe",
    "so",
    "dex",
    "jar",
    "class",
    "pdf",
    "doc",
    "docx",
    "xls",
    "xlsx",
    "ppt",
    "pptx",
    "py",
    "js",
)

/**
 * 判断目录名是否属于系统隐藏目录或明确的非漫画应用（浏览器/聊天/网盘）目录。
 */
internal fun shouldSkipDirectoryName(dirName: String): Boolean {
    val trimmed = dirName.trim()
    if (trimmed.isEmpty()) return true
    if (trimmed.startsWith('.')) return true
    return trimmed.lowercase() in SKIPPED_DIRECTORY_NAMES
}

/**
 * 判断路径是否为通用存储根目录或 `/sdcard/Download` 根自身，禁止将根下的零散图片直接当作单个画廊导入。
 */
internal fun isGenericDownloadRootPath(
    canonicalDirPath: String,
    externalStorageRoot: String,
): Boolean {
    val normalizedPath = canonicalDirPath.trimEnd('/').ifEmpty { "/" }
    val normalizedRoot = externalStorageRoot.trimEnd('/').ifEmpty { "/" }
    return normalizedPath == normalizedRoot ||
        normalizedPath == "$normalizedRoot/Download" ||
        normalizedPath == "$normalizedRoot/download"
}

/**
 * 校验本地目录内容是否符合画廊结构：
 * - 含 `.ehviewer` 或 `ComicInfo.xml` 时：至少 1 张真实图片即可；
 * - 无明确元数据时：存在软件/文档产物直接拒绝，且需达到最小图片张数（普通自动扫 >= 2 张）。
 */
internal fun isValidGalleryDirectoryEntries(
    entries: List<String>,
    hasExplicitMeta: Boolean,
    allowSingleImage: Boolean,
): Boolean {
    var imageCount = 0
    for (name in entries) {
        if (name.startsWith('.')) continue
        if (name.equals("thumb.webp", ignoreCase = true)) continue
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in DIRECTORY_FILTER_IMAGE_EXTENSIONS) {
            imageCount++
        } else if (!hasExplicitMeta && ext in NON_GALLERY_ARTIFACT_EXTENSIONS) {
            return false
        }
    }
    if (imageCount <= 0) return false
    if (hasExplicitMeta) return true
    return imageCount >= if (allowSingleImage) 1 else 2
}

/**
 * 校验压缩包（`.cbz` / `.zip`）内部是否真正构成漫画画廊：
 * - `.cbz` 含至少 1 张有效图片即可；
 * - 普通 `.zip` 若内含 `.ehviewer` 或 `ComicInfo.xml` 且有图则直接通过；否则拒绝含程序/办公文档的压缩包，
 *   并要求至少包含 3 张图片且图片占有效文件数 >= 50%。
 */
internal fun isValidGalleryArchiveEntries(
    fileName: String,
    entryNames: List<String>,
): Boolean {
    val archiveExt = fileName.substringAfterLast('.', "").lowercase()
    var hasMeta = false
    var imageCount = 0
    var otherFileCount = 0
    for (rawName in entryNames) {
        if (rawName.endsWith('/')) continue
        val normalized = rawName.replace('\\', '/')
        if (normalized.startsWith("__MACOSX/") || normalized.contains("/__MACOSX/")) continue
        val base = normalized.substringAfterLast('/')
        if (base.startsWith("._")) continue
        if (base == COMIC_INFO_FILE || base == SPIDER_INFO_FILENAME) {
            hasMeta = true
            continue
        }
        if (base.equals("thumb.webp", ignoreCase = true)) continue
        val ext = base.substringAfterLast('.', "").lowercase()
        if (ext in DIRECTORY_FILTER_IMAGE_EXTENSIONS) {
            imageCount++
        } else if (ext.isNotEmpty()) {
            if (archiveExt != "cbz" && ext in NON_GALLERY_ARTIFACT_EXTENSIONS) {
                return false
            }
            otherFileCount++
        }
    }
    if (imageCount <= 0) return false
    if (archiveExt == "cbz" || hasMeta) return true
    val totalFiles = imageCount + otherFileCount
    return imageCount >= 3 && totalFiles > 0 && imageCount * 2 >= totalFiles
}

/**
 * 解析压缩包对应的 GID：文件名以 5 位及以上 E-Hentai GID 开头时复用该正整数 GID，以自动合并同画廊目录；
 * 否则回退到基于规范化路径的稳定负数本地 GID。
 */
internal fun resolveArchiveGid(
    fileName: String,
    canonicalFilePath: String,
): Long {
    val base = fileName.substringBeforeLast('.', fileName)
    val dashCandidate = base.substringBefore('-').trim().toLongOrNull()
    val underCandidate = base.substringBefore('_').trim().toLongOrNull()
    val parsed = dashCandidate ?: underCandidate
    if (parsed != null && parsed >= MIN_FOLDER_GALLERY_GID) {
        return parsed
    }
    return LocalGalleryScanner.stableLocalArchiveGid(canonicalFilePath)
}

/**
 * 根据磁盘真实图片数量与 `.ehviewer` 元数据记录页数判断下载完成态：
 * 当位于应用主下载目录且具备有效远程标识、实际落地页数少于 `spiderPages` 时，标记为未下完可续传状态。
 */
internal fun resolveLocalDownloadCompletion(
    actualImages: Int,
    spiderPages: Int?,
    isInsideAppDownloadDir: Boolean,
    hasValidRemoteIdentity: Boolean,
): Pair<Int, Int> {
    if (isInsideAppDownloadDir &&
        hasValidRemoteIdentity &&
        spiderPages != null &&
        spiderPages > actualImages
    ) {
        return spiderPages to DownloadInfo.STATE_NONE
    }
    return actualImages to DownloadInfo.STATE_FINISH
}

internal data class ImportTarget(
    val dirname: String,
    val label: String?,
    val isInsideAppDownloadDir: Boolean,
)

private const val MIN_FOLDER_GALLERY_GID = 10_000L

/**
 * 解析本地目录对应的画廊 GID：
 * - 优先使用 `.ehviewer` 或 `ComicInfo.xml` 中明确记录的 E-Hentai GID；
 * - 对无元数据的纯目录名，仅当 `-` / `_` 前缀为至少 5 位（>= 10000）的纯数字时视为 E-Hentai GID，
 *   避免把 `2023-05 旅行`、`001-合集` 等年份或编号前缀误判为线上画廊 GID；
 * - 其余目录分配基于规范化目录路径的稳定负数本地 GID。
 */
internal fun resolveFolderGid(
    folderName: String,
    spiderGid: Long?,
    parsedGid: Long?,
    canonicalDirPath: String,
): Long {
    spiderGid?.takeIf { it > 0L }?.let { return it }
    parsedGid?.takeIf { it > 0L }?.let { return it }
    val dashCandidate = folderName.substringBefore('-').trim().toLongOrNull()
    val underCandidate = folderName.substringBefore('_').trim().toLongOrNull()
    val folderGid = dashCandidate ?: underCandidate
    if (folderGid != null && folderGid >= MIN_FOLDER_GALLERY_GID) {
        return folderGid
    }
    return LocalGalleryScanner.stableLocalDirGid(canonicalDirPath)
}

/**
 * 有上限地读取输入流字节（不关闭原流，由调用方负责如 `ZipInputStream` 仅消费当前 entry），
 * 防止 SAF 等未知长度流把超大单图或恶意载荷全量读入内存导致 OOM。
 */
internal fun readStreamBytesLimited(input: InputStream, maxBytes: Long): ByteArray? {
    if (maxBytes <= 0L) return null
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read <= 0) break
        total += read
        if (total > maxBytes) return null
        output.write(buffer, 0, read)
    }
    return if (total > 0L) output.toByteArray() else null
}

private val GALLERY_HEX_TOKEN_REGEX = Regex("^[0-9a-fA-F]{10}$")

/**
 * 校验字符串是否为规范的 10 位十六进制 E-Hentai 画廊 Token。
 */
internal fun isValidGalleryHexToken(token: String?): Boolean =
    !token.isNullOrBlank() && GALLERY_HEX_TOKEN_REGEX.matches(token.trim())

/**
 * 基于文件头魔数识别图片字节是否为真动图：
 * - 支持 `GIF87a` / `GIF89a`；
 * - 支持 RIFF WebP 容器内包含 `ANIM` 块的动态 WebP（自动排除静态 WebP）。
 */
fun isAnimatedImageHeader(bytes: ByteArray, length: Int = bytes.size): Boolean {
    val limit = minOf(length, bytes.size)
    if (limit >= 6 &&
        bytes[0] == 'G'.code.toByte() &&
        bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() &&
        bytes[3] == '8'.code.toByte() &&
        (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) &&
        bytes[5] == 'a'.code.toByte()
    ) {
        return true
    }
    if (limit >= 12 &&
        bytes[0] == 'R'.code.toByte() &&
        bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() &&
        bytes[3] == 'F'.code.toByte() &&
        bytes[8] == 'W'.code.toByte() &&
        bytes[9] == 'E'.code.toByte() &&
        bytes[10] == 'B'.code.toByte() &&
        bytes[11] == 'P'.code.toByte()
    ) {
        var offset = 12
        while (offset + 8 <= limit) {
            if (bytes[offset] == 'A'.code.toByte() &&
                bytes[offset + 1] == 'N'.code.toByte() &&
                bytes[offset + 2] == 'I'.code.toByte() &&
                bytes[offset + 3] == 'M'.code.toByte()
            ) {
                return true
            }
            val chunkSize = ((bytes[offset + 4].toLong() and 0xFFL)) or
                ((bytes[offset + 5].toLong() and 0xFFL) shl 8) or
                ((bytes[offset + 6].toLong() and 0xFFL) shl 16) or
                ((bytes[offset + 7].toLong() and 0xFFL) shl 24)
            val paddedSize = chunkSize + (chunkSize and 1L)
            val nextOffset = offset + 8L + paddedSize
            if (nextOffset <= offset || nextOffset > limit) break
            offset = nextOffset.toInt()
        }
    }
    return false
}

/**
 * 读取磁盘图片文件前 4 KiB 判断是否为 GIF / 动态 WebP 动图，避免全量解码耗时。
 */
fun isAnimatedImageFile(file: File): Boolean = runCatching {
    if (!file.isFile) return@runCatching false
    file.inputStream().use { input ->
        val buf = ByteArray(4096)
        val read = input.read(buf)
        read > 0 && isAnimatedImageHeader(buf, read)
    }
}.getOrDefault(false)

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
