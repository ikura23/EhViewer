package com.hippo.ehviewer.download

import arrow.fx.coroutines.parMap
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.exists
import com.ehviewer.core.files.find
import com.ehviewer.core.files.isFile
import com.ehviewer.core.files.list
import com.ehviewer.core.files.metadataOrNull
import com.ehviewer.core.files.mkdirs
import com.ehviewer.core.files.moveTo
import com.ehviewer.core.files.read
import com.ehviewer.core.files.sendTo
import com.ehviewer.core.files.write
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.parser.GalleryDetailUrlParser
import com.hippo.ehviewer.spider.SpiderInfo
import com.hippo.ehviewer.spider.SpiderQueen.Companion.SPIDER_INFO_FILENAME
import com.hippo.ehviewer.spider.getGalleryDownloadDir
import com.hippo.ehviewer.spider.perFilename
import com.hippo.ehviewer.spider.readCompatFromPath
import com.hippo.ehviewer.spider.readFromCache
import com.hippo.ehviewer.spider.saveToCache
import com.hippo.ehviewer.spider.write
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.util.displayString
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.asInputStream
import kotlinx.io.readByteArray
import moe.tarsin.coroutines.runSuspendCatching
import okio.Path
import splitties.init.appCtx

/**
 * 画廊更新增量准备结果。
 */
data class IncrementalPrepareResult(
    val reusedCount: Int,
    val totalPages: Int,
    val remainingCount: Int,
    val oldGid: Long,
    val inheritedLabel: String?,
)

enum class UpdateStage {
    IDLE,
    FETCHING_DETAIL,
    COMPARING_PAGES,
    COPYING_REUSABLE,
    STARTING_DOWNLOAD,
}

data class GalleryUpdateProgress(
    val running: Boolean = false,
    val targetGid: Long = 0L,
    val title: String = "",
    val stage: UpdateStage = UpdateStage.IDLE,
    val current: Int = 0,
    val total: Int = 0,
    val reusedCount: Int = 0,
    val remainingCount: Int = 0,
    val finishedEventId: Int = 0,
    val errorMessage: String? = null,
)

/**
 * 全局后台画廊更新调度器（脱离详情页生命周期）。
 *
 * 点选增量更新后在全局 IO 作用域执行详情拉取、页面指纹比对与本地页面复用复制，
 * 随后自动调用 `DownloadService.startDownload` 加入下载大界面队列；用户切离详情页也不会中断。
 */
object GalleryUpdateTracker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private val _progress = MutableStateFlow(GalleryUpdateProgress())
    val progress: StateFlow<GalleryUpdateProgress> = _progress.asStateFlow()

    fun launchIncrementalUpdate(
        oldInfo: DownloadInfo,
        targetGid: Long,
        targetToken: String,
        titleHint: String,
        targetLabel: String?,
        preloadedTargetInfo: BaseGalleryInfo? = null,
        replaceOld: Boolean = true,
    ): Boolean {
        synchronized(stateLock) {
            if (_progress.value.running) return false
            _progress.update {
                it.copy(
                    running = true,
                    targetGid = targetGid,
                    title = titleHint.ifBlank { EhUtils.getSuitableTitle(oldInfo) },
                    stage = if (preloadedTargetInfo == null) UpdateStage.FETCHING_DETAIL else UpdateStage.COMPARING_PAGES,
                    current = 0,
                    total = 0,
                    reusedCount = 0,
                    remainingCount = 0,
                    errorMessage = null,
                )
            }
        }
        scope.launch {
            var prepResult: IncrementalPrepareResult? = null
            var errorMsg: String? = null
            var registeredNewGid: Long? = null
            try {
                val targetInfo = preloadedTargetInfo ?: EhEngine.getGalleryDetail(
                    EhUrl.getGalleryDetailUrl(targetGid, targetToken),
                ).galleryInfo
                val resolvedTitle = EhUtils.getSuitableTitle(targetInfo)
                _progress.update {
                    if (!it.running) it else it.copy(
                        title = resolvedTitle,
                        stage = UpdateStage.COMPARING_PAGES,
                        current = 0,
                        total = 0,
                    )
                }
                val prep = prepareIncrementalUpdate(oldInfo, targetInfo) { stage, cur, tot ->
                    _progress.update {
                        if (!it.running) it else it.copy(stage = stage, current = cur, total = tot)
                    }
                }
                prepResult = prep
                if (replaceOld) {
                    DownloadManager.registerUpgradeReplacement(targetInfo.gid, oldInfo.gid)
                    writeUpgradeReplaceMarker(getGalleryDownloadDir(targetInfo), oldInfo.gid)
                    registeredNewGid = targetInfo.gid
                } else {
                    DownloadManager.clearUpgradeReplacement(targetInfo.gid)
                    deleteUpgradeReplaceMarker(getGalleryDownloadDir(targetInfo))
                }
                _progress.update {
                    if (!it.running) it else it.copy(
                        stage = UpdateStage.STARTING_DOWNLOAD,
                        current = 0,
                        total = 0,
                        reusedCount = prep.reusedCount,
                        remainingCount = prep.remainingCount,
                    )
                }
                withContext(Dispatchers.Main) {
                    with(appCtx) {
                        DownloadService.startDownload(targetInfo, targetLabel)
                    }
                }
            } catch (e: Throwable) {
                logcat(TAG, e)
                registeredNewGid?.let { gid ->
                    runSuspendCatching { DownloadManager.clearUpgradeReplacement(gid) }
                    runCatching { deleteUpgradeReplaceMarker(getGalleryDownloadDir(BaseGalleryInfo(gid, ""))) }
                }
                errorMsg = e.displayString()
            } finally {
                synchronized(stateLock) {
                    _progress.update { prev ->
                        prev.copy(
                            running = false,
                            stage = UpdateStage.IDLE,
                            reusedCount = prepResult?.reusedCount ?: prev.reusedCount,
                            remainingCount = prepResult?.remainingCount ?: prev.remainingCount,
                            finishedEventId = prev.finishedEventId + 1,
                            errorMessage = errorMsg,
                        )
                    }
                }
            }
        }
        return true
    }
}

private const val TAG = "GalleryUpdateHelper"
const val UPGRADE_REPLACE_FILENAME = ".ehviewer.replaces"
private val ENTRY_INDEX_REGEX = Regex("""^(?:.*/)?0*(\d+)\.([A-Za-z0-9]+)$""")

/**
 * 将待替换的旧版 GID 持久化写入新版画廊目录内的标记文件，
 * 避免用户在更新途中重装或覆盖安装 App 导致旧版替换关系丢失。
 */
internal fun writeUpgradeReplaceMarker(dir: Path, oldGid: Long) {
    if (oldGid <= 0L) return
    runCatching {
        val raw = dir.toString()
        if (!raw.startsWith("content:")) {
            val physicalDir = File(raw)
            if (!physicalDir.exists()) physicalDir.mkdirs()
            File(physicalDir, UPGRADE_REPLACE_FILENAME).writeText(oldGid.toString(), Charsets.UTF_8)
        } else {
            dir.mkdirs()
            dir.resolve(UPGRADE_REPLACE_FILENAME).write {
                write(oldGid.toString().toByteArray(Charsets.UTF_8))
            }
        }
    }.onFailure { logcat(TAG, it) }
}

/**
 * 从指定目录读取待替换旧版 GID（兼容直接物理路径及 SAF 文档路径）。
 */
internal fun readUpgradeReplaceMarker(dir: Path?): Long? {
    dir ?: return null
    return runCatching {
        val raw = dir.toString()
        val text = if (!raw.startsWith("content:")) {
            val file = File(raw, UPGRADE_REPLACE_FILENAME)
            if (!file.isFile) return@runCatching null
            file.readText(Charsets.UTF_8)
        } else {
            val marker = dir.resolve(UPGRADE_REPLACE_FILENAME)
            if (!marker.exists() || !marker.isFile) return@runCatching null
            marker.read { readByteArray().toString(Charsets.UTF_8) }
        }
        text.trim().toLongOrNull()?.takeIf { it > 0L }
    }.getOrNull()
}

/**
 * 删除指定目录内的升级替换标记文件。
 */
internal fun deleteUpgradeReplaceMarker(dir: Path?) {
    dir ?: return
    runCatching {
        val raw = dir.toString()
        if (!raw.startsWith("content:")) {
            File(raw, UPGRADE_REPLACE_FILENAME).takeIf { it.exists() }?.delete()
        } else {
            dir.resolve(UPGRADE_REPLACE_FILENAME).delete()
        }
    }
}

/**
 * 从详情页的 `parent` 链接中提取父画廊 GID。
 */
fun parseParentGid(parentUrl: String?): Long? =
    GalleryDetailUrlParser.parse(parentUrl, strict = false)?.gid

/**
 * 从 `newerVersions` 列表中选出最新（最后一个）版本画廊。
 */
fun latestNewerVersion(newerVersions: List<BaseGalleryInfo>): BaseGalleryInfo? =
    newerVersions.lastOrNull()

/**
 * 基于 E-Hentai 页面指纹（`pToken`）计算新版可复用的页码映射：`newIndex -> oldIndex`。
 * 允许中间插页、删页或重排；忽略空白无效 `pToken`。
 */
fun computeReusablePageMapping(
    oldPTokens: Map<Int, String>,
    newPTokens: Map<Int, String>,
): Map<Int, Int> {
    if (oldPTokens.isEmpty() || newPTokens.isEmpty()) return emptyMap()
    val oldIndexByToken = hashMapOf<String, Int>()
    for ((oldIdx, token) in oldPTokens) {
        if (oldIdx >= 0 && token.isNotBlank()) {
            oldIndexByToken.putIfAbsent(token, oldIdx)
        }
    }
    if (oldIndexByToken.isEmpty()) return emptyMap()
    val result = linkedMapOf<Int, Int>()
    for ((newIdx, token) in newPTokens) {
        if (newIdx >= 0 && token.isNotBlank()) {
            oldIndexByToken[token]?.let { oldIdx ->
                result[newIdx] = oldIdx
            }
        }
    }
    return result
}

/**
 * 从压缩包内条目的文件名或散图文件名中解析 0-based 页码索引（如 `00000001.jpg` -> `0`）。
 */
fun extractIndexFromEntryName(entryName: String): Int? {
    val match = ENTRY_INDEX_REGEX.matchEntire(entryName.replace('\\', '/')) ?: return null
    val oneBased = match.groupValues[1].toIntOrNull() ?: return null
    return (oneBased - 1).takeIf { it >= 0 }
}

/**
 * 判断本地已持有的旧版 `SpiderInfo` 是否已经覆盖全部本地图片所需的页指纹（`pToken`），
 * 避免对大画廊无意义地重爬旧版全部预览网页。
 */
internal fun hasAllRequiredOldPTokens(
    oldSpiderInfo: SpiderInfo?,
    existingLocalIndices: Set<Int>,
    hasArchive: Boolean,
): Boolean {
    if (!hasArchive && existingLocalIndices.isEmpty()) return true
    if (oldSpiderInfo == null || oldSpiderInfo.pages <= 0) return false
    if (hasArchive) return oldSpiderInfo.pTokenMap.size >= oldSpiderInfo.pages
    return existingLocalIndices.all { !oldSpiderInfo.pTokenMap[it].isNullOrBlank() }
}

/**
 * 为更新版画廊提前准备 `SpiderInfo` 并从本地旧版画廊（散图目录或 `.cbz`/`.zip`）复用未修改页面，
 * 避免重复下载已有页面。
 */
suspend fun prepareIncrementalUpdate(
    oldInfo: DownloadInfo,
    newInfo: BaseGalleryInfo,
    onProgress: (UpdateStage, Int, Int) -> Unit = { _, _, _ -> },
): IncrementalPrepareResult {
    onProgress(UpdateStage.COMPARING_PAGES, 0, 0)
    val oldImagesByIndex = hashMapOf<Int, Path>()
    oldInfo.downloadDir?.let { oldImagesByIndex.putAll(indexDirectoryImages(it)) }
    oldInfo.tempDownloadDir?.let { oldImagesByIndex.putAll(indexDirectoryImages(it)) }
    val archiveFile = oldInfo.archiveFile

    val newSpiderInfo = fetchCompleteSpiderInfo(newInfo.gid, newInfo.token) { cur, tot ->
        onProgress(UpdateStage.COMPARING_PAGES, cur, tot)
    }
    val oldSpiderInfo = loadOrFetchOldSpiderInfo(
        oldInfo = oldInfo,
        existingLocalIndices = oldImagesByIndex.keys,
        hasArchive = archiveFile != null,
    )

    // 先建立新版画廊目录与文件名映射，并提前落盘新版 SpiderInfo，减少下载启动阶段重复联网
    val newDownloadDir = getGalleryDownloadDir(newInfo).apply { mkdirs() }
    newSpiderInfo.write(newDownloadDir.resolve(SPIDER_INFO_FILENAME))
    newSpiderInfo.saveToCache()

    val saveAsCbz = Settings.saveAsCbz.value
    val targetDir = if (saveAsCbz) {
        requireNotNull(newInfo.tempDownloadDir) { "External temp directory unavailable for CBZ archive" }.apply { mkdirs() }
    } else {
        newDownloadDir
    }

    val totalPages = newSpiderInfo.pages.coerceAtLeast(newInfo.pages)
    val existingTargetByIndex = indexDirectoryImages(targetDir)
    val reusedTargets = hashSetOf<Int>()

    for (index in existingTargetByIndex.keys) {
        if (index in 0 until totalPages) reusedTargets += index
    }

    val mapping = if (oldSpiderInfo != null) {
        computeReusablePageMapping(oldSpiderInfo.pTokenMap, newSpiderInfo.pTokenMap)
    } else {
        emptyMap()
    }

    if (mapping.isNotEmpty()) {
        val pendingCopyCount = mapping.count { (newIndex, oldIndex) ->
            newIndex !in reusedTargets && oldImagesByIndex.containsKey(oldIndex)
        }
        var copied = 0
        if (pendingCopyCount > 0) {
            onProgress(UpdateStage.COPYING_REUSABLE, 0, pendingCopyCount)
        }
        // 1. 优先从旧版散图目录（正式目录 + 临时目录）复制匹配页面
        if (oldImagesByIndex.isNotEmpty()) {
            for ((newIndex, oldIndex) in mapping) {
                if (newIndex in reusedTargets) continue
                val srcFile = oldImagesByIndex[oldIndex] ?: continue
                val ext = FileUtils.getExtensionFromFilename(srcFile.name)?.takeIf { it.isNotBlank() } ?: continue
                if (copyPathSafely(srcFile, targetDir.resolve(perFilename(newIndex, ext)))) {
                    reusedTargets += newIndex
                }
                copied++
                if (copied % 10 == 0 || copied == pendingCopyCount) {
                    onProgress(UpdateStage.COPYING_REUSABLE, copied, pendingCopyCount)
                }
            }
        }

        // 2. 若旧版为 .cbz / .zip 归档且仍有未复用页，顺序扫描压缩包提取匹配旧页
        if (archiveFile != null && reusedTargets.size < mapping.size) {
            val neededOldToNew = hashMapOf<Int, MutableList<Int>>()
            for ((newIndex, oldIndex) in mapping) {
                if (newIndex !in reusedTargets) {
                    neededOldToNew.getOrPut(oldIndex) { mutableListOf() }.add(newIndex)
                }
            }
            if (neededOldToNew.isNotEmpty()) {
                onProgress(UpdateStage.COPYING_REUSABLE, reusedTargets.size, mapping.size)
                extractMatchingFromArchive(archiveFile, neededOldToNew, targetDir, reusedTargets)
            }
        }
    }

    val reusedCount = reusedTargets.size.coerceAtMost(totalPages)
    val remainingCount = (totalPages - reusedCount).coerceAtLeast(0)
    return IncrementalPrepareResult(
        reusedCount = reusedCount,
        totalPages = totalPages,
        remainingCount = remainingCount,
        oldGid = oldInfo.gid,
        inheritedLabel = oldInfo.label,
    )
}

private suspend fun fetchCompleteSpiderInfo(
    gid: Long,
    token: String,
    onPageProgress: (Int, Int) -> Unit = { _, _ -> },
): SpiderInfo {
    // 若账户开启了多页阅读器（MPV），直接单次请求获取全部 pToken，大幅缩短超长画廊等待时间
    if (EhUtils.isMpvAvailable) {
        val mpvTokens = runSuspendCatching {
            EhEngine.getPTokenListFromMpv(gid, token)
        }.getOrNull()
        if (!mpvTokens.isNullOrEmpty()) {
            val info = SpiderInfo(gid, token, mpvTokens.size)
            mpvTokens.forEachIndexed { idx, pToken ->
                info.pTokenMap[idx] = pToken
            }
            onPageProgress(1, 1)
            return info
        }
    }

    val firstUrl = EhUrl.getGalleryDetailUrl(gid, token, 0)
    val firstPage = EhEngine.getPreviewList(firstUrl)
    val spiderInfo = SpiderInfo(gid, token, firstPage.total)
    val perPage = firstPage.previews.size
    firstPage.previews.forEach { preview ->
        spiderInfo.pTokenMap[preview.position] = preview.pToken
    }
    spiderInfo.previewPerPage = perPage

    if (perPage > 0 && firstPage.total > perPage) {
        val totalPreviewPages = (firstPage.total + perPage - 1) / perPage
        val completed = AtomicInteger(1)
        onPageProgress(1, totalPreviewPages)
        val concurrency = Settings.multiThreadDownload.value.coerceIn(2, 4)
        val restResults = (1 until totalPreviewPages).parMap(concurrency = concurrency) { pageIndex ->
            val pageUrl = EhUrl.getGalleryDetailUrl(gid, token, pageIndex)
            val res = EhEngine.getPreviewList(pageUrl)
            onPageProgress(completed.incrementAndGet(), totalPreviewPages)
            res.previews
        }
        for (previews in restResults) {
            for (preview in previews) {
                spiderInfo.pTokenMap[preview.position] = preview.pToken
            }
        }
    } else {
        onPageProgress(1, 1)
    }
    return spiderInfo
}

private suspend fun loadOrFetchOldSpiderInfo(
    oldInfo: DownloadInfo,
    existingLocalIndices: Set<Int>,
    hasArchive: Boolean,
): SpiderInfo? {
    val localInfo = oldInfo.downloadDir?.find(SPIDER_INFO_FILENAME)?.let { readCompatFromPath(it) }
        ?: readFromCache(oldInfo.gid)
    if (hasAllRequiredOldPTokens(localInfo, existingLocalIndices, hasArchive)) {
        return localInfo
    }
    // 仅当本地已有散图在 .ehviewer 里缺页指纹，或旧版为压缩包时才补抓旧版目录（若旧版已被删档则回退本地）
    return runSuspendCatching {
        fetchCompleteSpiderInfo(oldInfo.gid, oldInfo.token)
    }.onFailure {
        logcat(TAG) { "Fallback to cached/local SpiderInfo for old gid=${oldInfo.gid}: ${it.message}" }
    }.getOrNull() ?: localInfo
}

private fun indexDirectoryImages(dir: Path): Map<Int, Path> {
    if (!dir.exists()) return emptyMap()
    val result = hashMapOf<Int, Path>()
    for (file in dir.list()) {
        if (!file.isFile) continue
        val name = file.name
        if (name.endsWith(".tmp")) continue
        val index = extractIndexFromEntryName(name) ?: continue
        result.putIfAbsent(index, file)
    }
    return result
}

private fun commitStagedFile(staging: Path, dest: Path, expectedSize: Long?): Boolean = runCatching {
    val stagedSize = staging.metadataOrNull()?.size
    if (expectedSize != null) {
        if (expectedSize <= 0L) return@runCatching false
        if (stagedSize != null && stagedSize != expectedSize) return@runCatching false
    } else if (stagedSize != null && stagedSize <= 0L) {
        return@runCatching false
    }
    dest.delete()
    val moved = runCatching { staging moveTo dest }.isSuccess
    if (!moved) {
        runCatching {
            staging sendTo dest
        }.onFailure {
            runCatching { dest.delete() }
            throw it
        }
    }
    val destSize = dest.metadataOrNull()?.size
    if (!dest.exists() || (expectedSize != null && destSize != null && destSize != expectedSize) || (destSize != null && destSize <= 0L)) {
        runCatching { dest.delete() }
        return@runCatching false
    }
    true
}.onFailure {
    runCatching { dest.delete() }
    logcat(TAG, it)
}.getOrDefault(false).also {
    runCatching { staging.delete() }
}

private fun copyPathSafely(src: Path, dest: Path): Boolean {
    val staging = dest.parent?.resolve("${dest.name}.copy.tmp") ?: return false
    return runCatching {
        val srcSize = src.metadataOrNull()?.size
        if (srcSize != null && srcSize <= 0L) return@runCatching false
        staging.delete()
        src sendTo staging
        commitStagedFile(staging, dest, srcSize)
    }.onFailure {
        runCatching { staging.delete() }
        logcat(TAG, it)
    }.getOrDefault(false)
}

private fun extractMatchingFromArchive(
    archiveFile: Path,
    neededOldToNew: Map<Int, List<Int>>,
    targetDir: Path,
    reusedTargets: MutableSet<Int>,
) {
    runCatching {
        archiveFile.read {
            ZipInputStream(asInputStream()).use { zip ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        val oldIndex = extractIndexFromEntryName(entry.name)
                        val newIndices = if (oldIndex != null) neededOldToNew[oldIndex] else null
                        if (!newIndices.isNullOrEmpty()) {
                            val ext = FileUtils.getExtensionFromFilename(entry.name)?.takeIf { it.isNotBlank() } ?: "jpg"
                            val firstTarget = targetDir.resolve(perFilename(newIndices[0], ext))
                            val staging = targetDir.resolve("${firstTarget.name}.copy.tmp")
                            staging.delete()
                            var writtenBytes = 0L
                            val writeOk = runCatching {
                                staging.write {
                                    while (true) {
                                        val read = zip.read(buffer)
                                        if (read <= 0) break
                                        write(buffer, 0, read)
                                        writtenBytes += read
                                    }
                                }
                                writtenBytes > 0L
                            }.getOrDefault(false)
                            if (writeOk && commitStagedFile(staging, firstTarget, writtenBytes)) {
                                reusedTargets += newIndices[0]
                                for (i in 1 until newIndices.size) {
                                    val dupTarget = targetDir.resolve(perFilename(newIndices[i], ext))
                                    if (copyPathSafely(firstTarget, dupTarget)) {
                                        reusedTargets += newIndices[i]
                                    }
                                }
                            } else {
                                runCatching { staging.delete() }
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
    }.onFailure {
        logcat(TAG, it)
    }
}
