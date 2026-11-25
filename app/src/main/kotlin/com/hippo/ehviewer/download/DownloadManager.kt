/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.download

import android.content.ContentResolver
import android.net.Uri
import android.os.Environment
import android.util.SparseLongArray
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisallowComposableCalls
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.util.size
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import arrow.fx.coroutines.parMapNotNull
import com.ehviewer.core.data.model.asEntity
import com.ehviewer.core.database.model.DownloadArtist
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.database.model.DownloadLabel
import com.ehviewer.core.database.model.GalleryEntity
import com.ehviewer.core.files.delete
import com.ehviewer.core.files.find
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.isFile
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.files.toUri
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.model.TagNamespace
import com.ehviewer.core.preferences.edit
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.mapNotNull
import com.ehviewer.core.util.withUIContext
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.exception.FatalException
import com.hippo.ehviewer.spider.COMIC_INFO_FILE
import com.hippo.ehviewer.spider.SpeedTracker
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.spider.SpiderQueen.Companion.SPIDER_INFO_FILENAME
import com.hippo.ehviewer.spider.SpiderQueen.OnSpiderListener
import com.hippo.ehviewer.spider.downloadDirname
import com.hippo.ehviewer.spider.readComicInfo
import com.hippo.ehviewer.spider.readCompatFromPath
import com.hippo.ehviewer.spider.toSimpleTags
import com.hippo.ehviewer.util.AppConfig
import com.hippo.ehviewer.util.insertWith
import com.hippo.ehviewer.util.runAssertingNotMainThread
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import splitties.init.appCtx

object DownloadManager : CoroutineScope {
    override val coroutineContext = Dispatchers.IO + Job()

    // All download info list
    private val allInfoList = runAssertingNotMainThread { EhDB.getAllDownloadInfo() as MutableList }

    @Volatile
    private var infoSnapshot: List<DownloadInfo> = allInfoList.toList()

    val downloadInfoList: List<DownloadInfo>
        get() = infoSnapshot

    // All download info map
    private val mAllInfoMap = ConcurrentHashMap(allInfoList.associateBy { it.gid })

    // All labels without default label
    // Create the SnapshotStateList first in case the database query is time-consuming
    val labelList = mutableStateListOf<DownloadLabel>().apply {
        addAll(runAssertingNotMainThread { EhDB.getAllDownloadLabelList() })
    }

    // Store download info wait to start
    private val mWaitList = ArrayDeque<DownloadInfo>()

    // 进程重建后遗留的 WAIT / DOWNLOAD 状态属于上一次生命周期，统一归入恢复状态机接管。
    private val recoveryPrefs = appCtx.getSharedPreferences("download_recovery", android.content.Context.MODE_PRIVATE)
    // 记录「新版 GID -> 待替换旧版 GID」映射，新版全部下载完成后自动替换删除旧版。
    private val upgradePrefs = appCtx.getSharedPreferences("download_upgrade_map", android.content.Context.MODE_PRIVATE)
    private val recovery = DownloadRecovery(
        allInfoList.filter {
            it.state == DownloadInfo.STATE_WAIT || it.state == DownloadInfo.STATE_DOWNLOAD
        }.onEach { it.state = DownloadInfo.STATE_WAIT }.map { it.gid },
        DownloadRecovery.RetryState(
            recoveryPrefs.getInt("attempts", 0),
            recoveryPrefs.getBoolean("waiting_for_foreground", false),
        ),
    ) { retry ->
        val committed = runCatching {
            recoveryPrefs.edit().putInt("attempts", retry.attempts)
                .putBoolean("waiting_for_foreground", retry.waitingForForeground).commit()
        }.getOrDefault(false)
        if (!committed) {
            logcat(TAG, LogPriority.ERROR) { "Failed to persist download recovery retry state: $retry" }
        }
    }
    private var recoveryJob: Job? = null
    private var recoveryGeneration = 0L
    private val listener = AtomicReference<DownloadListener?>()
    private val mDownloadListener get() = listener.get()
    private var mCurrentTask: DownloadInfo? = null
    private var mCurrentSpider: SpiderQueen? = null
    private var spiderListener: TaskListener? = null

    private val mutableNotifyFlow = MutableSharedFlow<DownloadInfo>(extraBufferCapacity = 1)
    val notifyFlow = mutableNotifyFlow.asSharedFlow()

    private val commands = DownloadCommands(this) { logcat(it) }
    private val persistence = DownloadCommands(this) { logcat(it) }
    private var currentWrites: MutableList<Deferred<Result<Unit>>>? = null

    private suspend fun command(block: suspend () -> Unit) {
        commandResult(block)
    }

    private suspend fun <T> commandResult(block: suspend () -> T): T {
        val (result, writes) = commands.execute {
            val pending = mutableListOf<Deferred<Result<Unit>>>()
            currentWrites = pending
            try {
                block() to pending
            } finally {
                currentWrites = null
            }
        }
        writes.forEach { it.await().getOrThrow() }
        return result
    }

    private suspend inline fun mutateAllInfoList(block: (MutableList<DownloadInfo>) -> Unit) {
        sortMutex.withLock {
            block(allInfoList)
            infoSnapshot = allInfoList.toList()
        }
    }

    private fun persist(write: suspend () -> Unit) {
        val pending = currentWrites
        if (pending == null) persistence.post(write) else pending += persistence.enqueue(write)
    }

    private fun saveStates(infos: Collection<DownloadInfo>) {
        if (infos.isEmpty()) return
        val states = infos.map { it.downloadInfo.copy() }
        persist { EhDB.updateDownloadStates(states) }
    }

    private fun saveNew(info: DownloadInfo, history: Boolean = false) {
        val snapshot = info.copy(
            galleryInfo = info.galleryInfo.toEntitySnapshot(),
            downloadInfo = info.downloadInfo.copy(),
        )
        persist {
            EhDB.putDownloadInfo(snapshot)
            if (history) EhDB.putHistoryInfo(snapshot)
        }
    }

    private val sortMutex = Mutex()
    private val manualOrderPrefs = appCtx.getSharedPreferences("download_manual_order", android.content.Context.MODE_PRIVATE)
    private var manualOrder: List<Long> = loadManualOrder()

    @Volatile
    private var manualOrderRanks: Map<Long, Int> = rebuildManualRanks(manualOrder)

    var metadataRevision by mutableIntStateOf(0)
        private set

    private val sortMode
        get() = SortMode.from(Settings.downloadSortMode.value)

    private fun currentComparator() = sortMode.comparator(manualOrderRanks)

    var isInitialized by mutableStateOf(false)
        private set

    init {
        launch {
            val mode = sortMode
            if (mode != SortMode.Default) sortDownloads(mode)
            isInitialized = true
        }
    }

    private fun loadManualOrder(): List<Long> = manualOrderPrefs.getString("order", null)
        ?.split(',')
        ?.mapNotNull { it.toLongOrNull() }
        ?.distinct()
        .orEmpty()

    private fun rebuildManualRanks(order: List<Long>): Map<Long, Int> = buildMap(order.size) {
        order.forEachIndexed { index, gid -> put(gid, index) }
    }

    private fun saveManualOrder(order: List<Long>) {
        manualOrderPrefs.edit().putString("order", order.joinToString(",")).apply()
    }

    fun containLabel(label: String?): Boolean {
        if (label == null) {
            return false
        }
        return labelList.any { it.label == label }
    }

    fun containDownloadInfo(gid: Long) = mAllInfoMap.containsKey(gid)

    fun getDownloadInfo(gid: Long) = mAllInfoMap[gid]

    fun getDownloadState(gid: Long): Int {
        val info = mAllInfoMap[gid]
        return info?.state ?: DownloadInfo.STATE_INVALID
    }

    suspend fun setDownloadListener(owner: DownloadListener) = command {
        listener.set(owner)
        recovery.clearWaitingForForeground()
        cancelRecoveryRestart()
    }

    suspend fun detachDownloadListener(owner: DownloadListener, waitForForeground: Boolean) = command {
        if (!listener.compareAndSet(owner, null)) return@command
        // 保留等待队列在数据库中的 WAIT 意图，同时在服务退出时立即释放抓取任务与系统锁。
        val interrupted = mWaitList.toList() + listOfNotNull(mCurrentTask)
        recovery.add(interrupted.map { it.gid })
        mWaitList.clear()
        stopCurrentDownloadInternal(DownloadInfo.STATE_WAIT)
        interrupted.forEach { it.state = DownloadInfo.STATE_WAIT }
        saveStates(interrupted)
        interrupted.forEach { mutableNotifyFlow.emit(it) }
        if (waitForForeground) {
            deferRecoveryIfBackground()
            logcat(TAG) { "Download service timed out; recovery follows foreground availability" }
        }
        scheduleRecoveryRestart()
    }

    suspend fun resumeInterruptedDownloads(owner: DownloadListener) = command {
        if (listener.get() !== owner) return@command
        val interrupted = recovery.takePending().mapNotNull { mAllInfoMap[it] }
            .filter { it.state == DownloadInfo.STATE_WAIT }
        logcat(TAG) { "Resuming ${interrupted.size} system-interrupted downloads" }
        mWaitList.addAll(interrupted)
        saveStates(interrupted)
        ensureDownload()
    }

    suspend fun stickyRecoveryDelay() = commands.execute { recovery.nextDelayMillis() }

    suspend fun onAppForeground() = command {
        recovery.onForeground()
        cancelRecoveryRestart()
        scheduleRecoveryRestart(immediate = true)
    }

    suspend fun deferRecoveryUntilForeground() = command {
        if (listener.get() != null) return@command
        cancelRecoveryRestart()
        deferRecoveryIfBackground()
        scheduleRecoveryRestart()
    }

    private suspend fun deferRecoveryIfBackground() {
        val foreground = withUIContext {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        recovery.deferUntilForeground(foreground)
    }

    private fun cancelRecoveryRestart() {
        recoveryGeneration++
        recoveryJob?.cancel()
        recoveryJob = null
    }

    private fun scheduleRecoveryRestart(immediate: Boolean = false) {
        if (listener.get() != null || recoveryJob?.isActive == true) return
        val wait = recovery.nextDelayMillis(immediate) ?: return
        logcat(TAG) { "Download recovery scheduled in ${wait}ms (at most 5 attempts per foreground session)" }
        val generation = ++recoveryGeneration
        recoveryJob = launch {
            delay(wait)
            command {
                if (generation != recoveryGeneration || !recovery.hasPending || listener.get() != null) return@command
                with(appCtx) { DownloadService.startService(DownloadService.ACTION_RESUME) }
            }
            // 成功发起启动请求不代表系统已成功拉起服务；30 秒兜底复查一次重试。
            delay(30_000)
            command {
                if (generation != recoveryGeneration) return@command
                recoveryJob = null
                scheduleRecoveryRestart()
            }
        }
    }

    suspend fun stopServiceIfIdle(owner: DownloadListener, stop: () -> Unit) = commands.execute {
        if (listener.get() === owner && mCurrentTask == null && mWaitList.isEmpty()) stop()
    }

    private fun notifyIfIdle() {
        if (mCurrentTask == null && mWaitList.isEmpty()) mDownloadListener?.onIdle()
    }

    private suspend fun ensureDownload() {
        // 队列必须依托活跃的 foreground service 才能执行任务。
        if (listener.get() == null) return
        try {
            while (mCurrentTask == null && mWaitList.isNotEmpty()) {
                val info = mWaitList.removeFirst()
                mCurrentTask = info
                info.archiveFile?.let {
                    finishCurrent(info.pages, info.pages, info.pages, failed = false)
                    continue
                }
                with(info) {
                    state = DownloadInfo.STATE_DOWNLOAD
                    speed = -1
                    remaining = -1
                    total = -1
                    finished = 0
                    downloaded = 0
                    legacy = -1
                }
                val callback = TaskListener()
                spiderListener = callback
                val spider = SpiderQueen.obtainSpiderQueen(info, SpiderQueen.MODE_DOWNLOAD)
                mCurrentSpider = spider
                spider.addOnSpiderListener(callback)
                // Update in DB
                saveStates(listOf(info))
                // Start speed count
                mSpeedReminder.start(callback)
                // Notify start downloading
                mDownloadListener?.onStart(info)
                // Notify state update
                mutableNotifyFlow.emit(info)
            }
        } finally {
            notifyIfIdle()
        }
    }

    private fun enqueueDownloads(infos: List<DownloadInfo>, deferred: Boolean) {
        if (deferred) recovery.add(infos.map { it.gid }) else mWaitList.addAll(infos)
    }

    suspend fun startDownload(galleryInfo: BaseGalleryInfo, label: String?, deferred: Boolean = false) = command {
        if (mCurrentTask?.gid == galleryInfo.gid) {
            // It is current task
            return@command
        }

        // Check in download list
        var info = mAllInfoMap[galleryInfo.gid]
        if (info != null) { // Get it in download list
            // 网站给出的页数是权威值；若本地条目页数偏小（例如曾按已有文件数登记），以网站页数为准纠正。
            if (galleryInfo.pages > info.pages) {
                info.galleryInfo.pages = galleryInfo.pages
                info.pages = galleryInfo.pages
                info.downloaded = minOf(info.downloaded, info.pages)
                info.total = info.pages
                info.finished = minOf(info.finished, info.pages)
                val snapshot = info.galleryInfo.toEntitySnapshot()
                persist { EhDB.putGalleryInfo(snapshot) }
            }
            if (info.state != DownloadInfo.STATE_WAIT) {
                // Set state DownloadInfo.STATE_WAIT
                info.state = DownloadInfo.STATE_WAIT
                // Add to wait list
                enqueueDownloads(listOf(info), deferred)
                // Update in DB
                saveStates(listOf(info))
                // Notify state update
                mutableNotifyFlow.emit(info)
                // Make sure download is running
                if (!deferred) ensureDownload()
            }
        } else {
            // It is new download info
            info = DownloadInfo(galleryInfo.asEntity(), galleryInfo.downloadDirname())
            info.label = label
            info.state = DownloadInfo.STATE_WAIT
            // Add to all download list and map
            mutateAllInfoList {
                it.insertWith(info, currentComparator())
            }
            mAllInfoMap[galleryInfo.gid] = info

            // Add to wait list
            enqueueDownloads(listOf(info), deferred)

            // Save to
            saveNew(info, history = true)

            // Notify
            mutableNotifyFlow.emit(info)
            // Make sure download is running
            if (!deferred) ensureDownload()
        }
    }

    suspend fun startRangeDownload(gidList: LongArray, deferred: Boolean = false) = command {
        val updateList = gidList.distinct().mapNotNull { mAllInfoMap[it] }
            .filter {
                when (it.state) {
                    DownloadInfo.STATE_NONE, DownloadInfo.STATE_FAILED, DownloadInfo.STATE_FINISH -> true
                    else -> false
                }
            }
        if (updateList.isNotEmpty()) {
            updateList.onEach {
                it.state = DownloadInfo.STATE_WAIT
                mutableNotifyFlow.emit(it)
            }
            saveStates(updateList)
            enqueueDownloads(updateList, deferred)
            if (!deferred) ensureDownload()
        }
    }

    @Stable
    @Composable
    fun collectDownloadState(gid: Long): State<Int> = remember {
        notifyFlow.transform { if (it.gid == gid) emit(getDownloadState(gid)) }
    }.collectAsState(getDownloadState(gid))

    @Stable
    @Composable
    fun collectContainDownloadInfo(gid: Long): State<Boolean> = remember {
        notifyFlow.transform { if (it.gid == gid) emit(containDownloadInfo(gid)) }
    }.collectAsState(containDownloadInfo(gid))

    @Stable
    @Composable
    inline fun <T> updatedDownloadInfo(info: DownloadInfo, crossinline transform: @DisallowComposableCalls DownloadInfo.() -> T): T = remember {
        notifyFlow.transform { if (it.gid == info.gid) emit(transform(it)) }
    }.collectAsState(transform(info)).value

    suspend fun startAllDownload(deferred: Boolean = false) = command {
        val updateList = sortMutex.withLock {
            allInfoList.filter {
                when (it.state) {
                    DownloadInfo.STATE_NONE, DownloadInfo.STATE_FAILED -> true
                    else -> false
                }
            }
        }
        if (updateList.isNotEmpty()) {
            updateList.onEach {
                it.state = DownloadInfo.STATE_WAIT
                mutableNotifyFlow.emit(it)
            }
            saveStates(updateList)
            enqueueDownloads(updateList, deferred)
            if (!deferred) ensureDownload()
        }
    }

    suspend fun addDownload(downloadInfoList: List<DownloadInfo>) = command {
        val imported = downloadInfoList.distinctBy { it.gid }.filterNot { containDownloadInfo(it.gid) }.map { info ->
            val state = when (info.state) {
                DownloadInfo.STATE_WAIT, DownloadInfo.STATE_DOWNLOAD -> DownloadInfo.STATE_NONE
                else -> info.state
            }
            info.copy(
                galleryInfo = info.galleryInfo.toEntitySnapshot(),
                artistInfoList = info.artistInfoList.toList(),
                downloadInfo = info.downloadInfo.copy(state = state),
            )
        }
        val comparator = currentComparator()
        for (info in imported) {
            persistence.execute {
                EhDB.putDownloadInfo(info)
                EhDB.putDownloadArtist(info.gid, info.artistInfoList)
            }
            mutateAllInfoList { it.insertWith(info, comparator) }
            mAllInfoMap[info.gid] = info
        }
    }

    suspend fun addDownloadLabel(downloadLabelList: List<DownloadLabel>) = command {
        val offset = downloadLabelList.size
        downloadLabelList.forEachIndexed { index, label ->
            if (!containLabel(label.label)) {
                label.position = index + offset
                labelList.add(persistence.execute { EhDB.addDownloadLabel(label) })
            }
        }
    }

    suspend fun restoreDownload(galleryInfo: BaseGalleryInfo, dirname: String) = command {
        if (containDownloadInfo(galleryInfo.gid)) return@command
        val info = DownloadInfo(galleryInfo.asEntity(), dirname)
        info.state = DownloadInfo.STATE_NONE

        // Add to all download list and map
        mutateAllInfoList {
            it.insertWith(info, currentComparator())
        }
        mAllInfoMap[galleryInfo.gid] = info

        // Save to
        saveNew(info)

        // Notify
        mutableNotifyFlow.emit(info)
    }

    private fun extractArtistNames(tags: List<String>?): List<String> = tags.orEmpty()
        .mapNotNull { tag ->
            val idx = tag.indexOf(':')
            if (idx <= 0) return@mapNotNull null
            val ns = tag.substring(0, idx)
            val name = tag.substring(idx + 1).trim()
            if (name.isEmpty()) return@mapNotNull null
            if (ns == TagNamespace.Artist.prefix ||
                ns == TagNamespace.Artist.value ||
                ns == TagNamespace.Cosplayer.prefix ||
                ns == TagNamespace.Cosplayer.value
            ) {
                name
            } else {
                null
            }
        }
        .distinct()

    suspend fun importLocalDownload(
        galleryInfo: BaseGalleryInfo,
        absolutePath: String,
        pages: Int,
        label: String? = "本地",
    ): Boolean = commandResult {
        if (containDownloadInfo(galleryInfo.gid)) return@commandResult false
        if (label != null) addLabelOwned(label)
        galleryInfo.pages = pages
        val info = DownloadInfo(galleryInfo.asEntity(), absolutePath)
        info.state = DownloadInfo.STATE_FINISH
        info.pages = pages
        info.downloaded = pages
        info.total = pages
        info.finished = pages
        info.label = label
        val artistNames = extractArtistNames(galleryInfo.simpleTags)
        if (artistNames.isNotEmpty()) {
            info.artistInfoList = DownloadArtist.from(info.gid, artistNames)
        }
        mutateAllInfoList {
            it.insertWith(info, currentComparator())
        }
        mAllInfoMap[galleryInfo.gid] = info
        val snapshot = info.copy(
            galleryInfo = info.galleryInfo.toEntitySnapshot(),
            artistInfoList = info.artistInfoList.toList(),
            downloadInfo = info.downloadInfo.copy(),
        )
        persist {
            EhDB.putDownloadDirname(snapshot.gid, absolutePath)
            EhDB.putDownloadInfo(snapshot)
            if (snapshot.artistInfoList.isNotEmpty()) {
                EhDB.putDownloadArtist(snapshot.gid, snapshot.artistInfoList)
            }
        }
        metadataRevision++
        mutableNotifyFlow.emit(info)
        true
    }

    /**
     * 将从详情页或联网 API 补全到的完整画廊元数据同步回下载数据库与内存缓存，
     * 解决本地导入或旧条目退出详情页后仍显示 `UNKNOWN` 分类及“未知作者”的问题。
     */
    suspend fun syncGalleryMetadata(source: GalleryInfo): Boolean {
        val extractedTags = if (source is GalleryDetail) {
            source.tagGroups.flatMap { group ->
                val prefix = group.namespace.prefix ?: group.namespace.value
                group.tags.map { "$prefix:${it.text}" }
            }.ifEmpty { source.simpleTags }
        } else {
            source.simpleTags
        }
        val extractedArtists = if (source is GalleryDetail) {
            source.tagGroups
                .filter { it.namespace == TagNamespace.Artist || it.namespace == TagNamespace.Cosplayer }
                .flatMap { it.tags }
                .map { it.text.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        } else {
            extractArtistNames(extractedTags)
        }

        return commandResult {
            val old = mAllInfoMap[source.gid] ?: return@commandResult false
            val entity = old.galleryInfo
            var changed = false
            var artistsChanged = false

            val newTitle = source.title?.takeIf { it.isNotBlank() }
            if (newTitle != null && entity.title != newTitle) {
                entity.title = newTitle
                changed = true
            }
            val newTitleJpn = source.titleJpn?.takeIf { it.isNotBlank() }
            if (newTitleJpn != null && entity.titleJpn != newTitleJpn) {
                entity.titleJpn = newTitleJpn
                changed = true
            }
            if (source.category != 0 && entity.category != source.category) {
                entity.category = source.category
                changed = true
            }
            val newUploader = source.uploader?.takeIf { it.isNotBlank() }
            if (newUploader != null && entity.uploader != newUploader) {
                entity.uploader = newUploader
                changed = true
            }
            if (source.rating >= 0f && entity.rating != source.rating) {
                entity.rating = source.rating
                changed = true
            }
            val newPosted = source.posted?.takeIf { it.isNotBlank() }
            if (newPosted != null && entity.posted != newPosted) {
                entity.posted = newPosted
                changed = true
            }
            val newThumbKey = source.thumbKey?.takeIf { it.isNotBlank() }
            if (newThumbKey != null && entity.thumbKey != newThumbKey) {
                // 优先保留/更新为包含 `/` 的远程 thumbKey，不让本地相对文件名反向覆盖远程 key
                if ('/' in newThumbKey || entity.thumbKey.isNullOrBlank()) {
                    entity.thumbKey = newThumbKey
                    changed = true
                }
            }
            if (source.thumbWidth > 0 && entity.thumbWidth != source.thumbWidth) {
                entity.thumbWidth = source.thumbWidth
                changed = true
            }
            if (source.thumbHeight > 0 && entity.thumbHeight != source.thumbHeight) {
                entity.thumbHeight = source.thumbHeight
                changed = true
            }
            if (!extractedTags.isNullOrEmpty() && entity.simpleTags != extractedTags) {
                entity.simpleTags = extractedTags.toList()
                changed = true
            }
            val newLang = source.simpleLanguage?.takeIf { it.isNotBlank() }
            if (newLang != null && entity.simpleLanguage != newLang) {
                entity.simpleLanguage = newLang
                changed = true
            }
            if (entity.disowned != source.disowned) {
                entity.disowned = source.disowned
                changed = true
            }
            val targetPages = maxOf(entity.pages, source.pages)
            if (targetPages > entity.pages) {
                entity.pages = targetPages
                changed = true
            }
            if (extractedArtists.isNotEmpty()) {
                val oldArtists = old.artistInfoList.map { it.artist }
                if (oldArtists != extractedArtists) {
                    old.artistInfoList = DownloadArtist.from(old.gid, extractedArtists)
                    artistsChanged = true
                    changed = true
                }
            }

            if (!changed) return@commandResult false

            mutateAllInfoList { }
            val snapshot = entity.toEntitySnapshot()
            val artistsSnapshot = old.artistInfoList.toList()
            persist {
                EhDB.putGalleryInfo(snapshot)
                if (artistsChanged) {
                    EhDB.putDownloadArtist(old.gid, artistsSnapshot)
                }
            }
            metadataRevision++
            mutableNotifyFlow.emit(old)
            true
        }
    }

    suspend fun attachLocalDirname(
        gid: Long,
        absolutePath: String,
        thumbKey: String? = null,
        pages: Int = 0,
        title: String? = null,
    ): Boolean = commandResult {
        val old = mAllInfoMap[gid] ?: return@commandResult false
        // 仅允许已下载完成条目被本地目录接管，防止运行中的下载被中途截断页数。
        if (old.state != DownloadInfo.STATE_FINISH) return@commandResult false
        if (pages > 0 && old.pages > pages) return@commandResult false
        if (old.dirname == absolutePath &&
            (thumbKey == null || old.thumbKey == thumbKey) &&
            (title == null || old.title == title) &&
            (pages <= 0 || old.pages >= pages)
        ) {
            return@commandResult false
        }
        if (title != null) old.galleryInfo.title = title
        if (thumbKey != null) old.galleryInfo.thumbKey = thumbKey
        if (pages > 0 && old.pages < pages) old.galleryInfo.pages = pages
        val info = old.copy(dirname = absolutePath)
        mAllInfoMap[gid] = info
        mutateAllInfoList { list ->
            val idx = list.indexOfFirst { it.gid == gid }
            if (idx >= 0) {
                list[idx] = info
            } else {
                list.insertWith(info, currentComparator())
            }
        }
        val gallerySnapshot = info.galleryInfo.toEntitySnapshot()
        persist {
            EhDB.putDownloadDirname(gid, absolutePath)
            EhDB.putGalleryInfo(gallerySnapshot)
        }
        metadataRevision++
        mutableNotifyFlow.emit(info)
        true
    }

    /**
     * 纠正位于应用当前下载目录内的条目：若之前曾被本地扫描误标为「本地」标签，
     * 自动清除该标签并尽量将目录还原为相对子目录名（已有其他自定义分类标签则保留）。
     */
    suspend fun normalizeAppDownloadEntry(
        gid: Long,
        dirname: String,
        thumbKey: String? = null,
        pages: Int = 0,
        title: String? = null,
    ): Boolean = commandResult {
        val old = mAllInfoMap[gid] ?: return@commandResult false
        if (old.state != DownloadInfo.STATE_FINISH) return@commandResult false

        val normalizedLabel = if (old.label == "本地") null else old.label
        val labelChanged = old.label != normalizedLabel
        val dirnameChanged = old.dirname != dirname
        val thumbChanged = thumbKey != null && old.thumbKey.isNullOrBlank()
        val titleChanged = !title.isNullOrBlank() && old.title.isNullOrBlank()
        val pagesChanged = pages > 0 && old.pages < pages
        if (!labelChanged && !dirnameChanged && !thumbChanged && !titleChanged && !pagesChanged) {
            return@commandResult false
        }

        if (titleChanged) old.galleryInfo.title = title
        if (thumbChanged) old.galleryInfo.thumbKey = thumbKey
        if (pagesChanged) old.galleryInfo.pages = pages

        val info = old.copy(dirname = dirname)
        info.label = normalizedLabel
        mAllInfoMap[gid] = info
        mutateAllInfoList { list ->
            val idx = list.indexOfFirst { it.gid == gid }
            if (idx >= 0) {
                list[idx] = info
            } else {
                list.insertWith(info, currentComparator())
            }
        }
        val gallerySnapshot = info.galleryInfo.toEntitySnapshot()
        saveStates(listOf(info))
        persist {
            EhDB.putDownloadDirname(gid, dirname)
            EhDB.putGalleryInfo(gallerySnapshot)
        }
        metadataRevision++
        mutableNotifyFlow.emit(info)
        true
    }

    suspend fun stopDownload(gid: Long) = command {
        val info = stopDownloadInternal(gid)
        if (info != null) {
            // Update listener
            mutableNotifyFlow.emit(info)
            // Ensure download
            ensureDownload()
        }
    }

    suspend fun stopRangeDownload(gidList: LongArray) = command {
        stopRangeDownloadInternal(gidList)

        // Update listener
        gidList.mapNotNull { mAllInfoMap[it] }.forEach {
            mutableNotifyFlow.emit(it)
        }

        // Ensure download
        ensureDownload()
    }

    suspend fun stopAllDownload() = command { stopAllDownloadOwned() }

    private suspend fun stopAllDownloadOwned() {
        cancelRecoveryRestart()
        val interrupted = recovery.takePending().mapNotNull { mAllInfoMap[it] }
        interrupted.forEach {
            it.state = DownloadInfo.STATE_NONE
            mutableNotifyFlow.emit(it)
        }
        saveStates(interrupted)
        try {
            // Stop all in wait list
            for (info in mWaitList) {
                info.state = DownloadInfo.STATE_NONE
                // Notify
                mutableNotifyFlow.emit(info)
            }
            saveStates(mWaitList)
            mWaitList.clear()

            // Stop current
            val info = stopCurrentDownloadInternal()
            info?.let { mutableNotifyFlow.emit(it) }
        } finally {
            notifyIfIdle()
        }
    }

    suspend fun deleteDownload(gid: Long, deleteFiles: Boolean = false) = command {
        removeUpgradeMappingsFor(gid)
        stopDownloadInternal(gid)
        val info = mAllInfoMap[gid]
        if (info != null) {
            removeDownloadEntryOwned(info, deleteFiles = deleteFiles)
            // Ensure download
            ensureDownload()
        }
    }

    suspend fun deleteRangeDownload(gidList: LongArray) = command {
        gidList.forEach(::removeUpgradeMappingsFor)
        stopRangeDownloadInternal(gidList)
        val list = gidList.mapNotNull { gid ->
            mAllInfoMap.remove(gid)
        }
        persist { EhDB.removeDownloadInfo(list) }
        val localGids = list.filter { it.isLocal }.map { it.gid }
        if (localGids.isNotEmpty()) {
            persist { localGids.forEach { EhDB.removeDownloadDirname(it) } }
        }
        mutateAllInfoList {
            it.removeAll(list.toSet())
        }

        // Update listener
        list.forEach { mutableNotifyFlow.emit(it) }

        // Ensure download
        ensureDownload()
    }

    /**
     * 登记画廊增量升级替换关系：待 [newGid] 下载全部完成后自动迁移阅读进度并移除 [oldGid]。
     */
    suspend fun registerUpgradeReplacement(newGid: Long, oldGid: Long) = command {
        if (newGid == oldGid) return@command
        upgradePrefs.edit().putLong(newGid.toString(), oldGid).apply()
    }

    private fun removeUpgradeMappingsFor(gid: Long) {
        val editor = upgradePrefs.edit()
        var changed = false
        val gidKey = gid.toString()
        if (upgradePrefs.contains(gidKey)) {
            editor.remove(gidKey)
            changed = true
        }
        for ((k, v) in upgradePrefs.all) {
            if (v is Long && v == gid) {
                editor.remove(k)
                changed = true
            }
        }
        if (changed) editor.apply()
    }

    private suspend fun removeDownloadEntryOwned(
        info: DownloadInfo,
        deleteFiles: Boolean,
        preserveDir: Path? = null,
    ) {
        persist { EhDB.removeDownloadInfo(info) }
        mutateAllInfoList {
            it.remove(info)
        }
        mAllInfoMap.remove(info.gid)
        mutableNotifyFlow.emit(info)

        if (info.isLocal) {
            // 本地导入条目只清理数据库目录映射，绝不删原磁盘文件，避免重新在线下载时误写入原位置。
            persist { EhDB.removeDownloadDirname(info.gid) }
        } else if (deleteFiles) {
            val oldDir = info.downloadDir
            if (oldDir != null && oldDir != preserveDir) {
                oldDir.delete()
            }
            val oldTempDir = info.tempDownloadDir
            if (oldTempDir != null && oldTempDir != preserveDir) {
                oldTempDir.delete()
            }
            persist { EhDB.removeDownloadDirname(info.gid) }
        }
    }

    suspend fun sortDownloads(mode: SortMode) = mutateAllInfoList {
        it.sortWith(mode.comparator(manualOrderRanks))
    }

    /**
     * 更新并持久化用户手动拖拽排列的下载画廊顺序。
     */
    suspend fun updateManualOrder(visibleOrderedGids: List<Long>) = command {
        if (visibleOrderedGids.isEmpty()) return@command
        val existingSet = manualOrder.toHashSet()
        val baseOrder = manualOrder + allInfoList.map { it.gid }.filter { it !in existingSet }
        val merged = mergeManualOrder(baseOrder, visibleOrderedGids)
        manualOrder = merged
        manualOrderRanks = rebuildManualRanks(merged)
        saveManualOrder(merged)
        if (sortMode.field == SortMode.Field.CUSTOM) {
            mutateAllInfoList { list ->
                list.sortWith(sortMode.comparator(manualOrderRanks))
            }
        }
        metadataRevision++
    }

    suspend fun resetAllReadingProgress() = runCatching {
        EhDB.clearProgressInfo()
    }.onFailure { logcat(it) }

    // Update in DB
    // Update listener
    // No ensureDownload
    private suspend fun stopDownloadInternal(gid: Long): DownloadInfo? {
        if (recovery.remove(gid)) {
            val info = mAllInfoMap[gid] ?: return null
            info.state = DownloadInfo.STATE_NONE
            saveStates(listOf(info))
            if (!recovery.hasPending) cancelRecoveryRestart()
            return info
        }
        // Check current task
        if (mCurrentTask?.gid == gid) {
            // Stop current
            return stopCurrentDownloadInternal()
        }
        val iterator = mWaitList.iterator()
        while (iterator.hasNext()) {
            val info = iterator.next()
            if (info.gid == gid) {
                // Remove from wait list
                iterator.remove()
                // Update state
                info.state = DownloadInfo.STATE_NONE
                // Update in DB
                saveStates(listOf(info))
                return info
            }
        }
        return null
    }

    // Update in DB
    // Update mDownloadListener
    private suspend fun stopCurrentDownloadInternal(state: Int = DownloadInfo.STATE_NONE): DownloadInfo? {
        val info = mCurrentTask
        val spider = mCurrentSpider
        // Release spider
        if (spider != null) {
            spiderListener?.let(spider::removeOnSpiderListener)
            SpiderQueen.releaseSpiderQueen(spider, SpiderQueen.MODE_DOWNLOAD)
        }
        mCurrentTask = null
        mCurrentSpider = null
        spiderListener = null
        // Stop speed reminder
        mSpeedReminder.stop()
        if (info == null) {
            return null
        }

        // Update state
        info.state = state
        // Update in DB
        saveStates(listOf(info))
        // Listener
        mDownloadListener?.onCancel(info)
        return info
    }

    // Update in DB
    // Update mDownloadListener
    private suspend fun stopRangeDownloadInternal(gidList: LongArray) {
        gidList.filter { recovery.contains(it) }.forEach { stopDownloadInternal(it) }
        // Two way
        if (gidList.size < mWaitList.size) {
            for (element in gidList) {
                stopDownloadInternal(element)
            }
        } else {
            // Check current task
            if (mCurrentTask != null && gidList.contains(mCurrentTask!!.gid)) {
                // Stop current
                stopCurrentDownloadInternal()
            }

            // Check all in wait list
            val stopped = mutableListOf<DownloadInfo>()
            val iterator = mWaitList.iterator()
            while (iterator.hasNext()) {
                val info = iterator.next()
                if (gidList.contains(info.gid)) {
                    // Remove from wait list
                    iterator.remove()
                    // Update state
                    info.state = DownloadInfo.STATE_NONE
                    // Update in DB
                    stopped += info
                }
            }
            saveStates(stopped)
        }
    }

    /**
     * @param label Not allow new label
     */
    suspend fun changeLabel(list: Collection<DownloadInfo>, label: String?) = command {
        if (null != label && !containLabel(label)) {
            logcat(TAG, LogPriority.ERROR) { "Not exits label: $label" }
            return@command
        }
        list.forEach {
            it.label = label
        }
        saveStates(list)
    }

    suspend fun addLabel(label: String?) = command { addLabelOwned(label) }

    private suspend fun addLabelOwned(label: String?) {
        if (label == null || containLabel(label)) {
            return
        }
        val row = DownloadLabel(label, labelList.size)
        labelList.add(persistence.execute { EhDB.addDownloadLabel(row) })
    }

    suspend fun renameLabel(from: String, to: String) = command {
        if (from == to || containLabel(to)) return@command
        val index = labelList.indexOfFirst { it.label == from }
        if (index != -1) {
            val new = labelList[index].copy(label = to)
            persistence.execute { EhDB.updateDownloadLabel(new) }
            labelList[index] = new
            sortMutex.withLock {
                allInfoList.forEach {
                    if (it.label == from) {
                        it.label = to
                    }
                }
            }
        }
    }

    suspend fun deleteLabel(label: String) = command {
        with(labelList) {
            val index = indexOfFirst { it.label == label }
            if (index < 0) return@command
            val item = get(index)
            persistence.execute { EhDB.removeDownloadLabel(item) }
            subList(index + 1, size).forEach {
                it.position--
            }
            removeAt(index)
        }
        sortMutex.withLock {
            allInfoList.forEach {
                if (it.label == label) {
                    it.label = null
                }
            }
        }
    }

    suspend fun readMetadataFromLocal() {
        val list = sortMutex.withLock {
            allInfoList.mapNotNull {
                val updateGallery = it.pages == 0 || it.simpleTags == null
                val updateArtist = it.artistInfoList.isEmpty()
                if (updateGallery || updateArtist) {
                    Triple(it, updateGallery, updateArtist)
                } else {
                    null
                }
            }
        }.parMapNotNull(concurrency = 5) { (info, updateGallery, updateArtist) ->
            info.downloadDir?.run {
                val comicInfo = find(COMIC_INFO_FILE)?.let { readComicInfo(it) }
                if (comicInfo != null) {
                    val galleryInfo = if (updateGallery) {
                        info.pages = comicInfo.pageCount
                        info.simpleTags = comicInfo.toSimpleTags()
                        info.galleryInfo
                    } else {
                        null
                    }

                    val artistList = if (updateArtist && comicInfo.penciller != null) {
                        info.artistInfoList = DownloadArtist.from(info.gid, comicInfo.penciller)
                        info.gid to info.artistInfoList
                    } else {
                        null
                    }

                    if (galleryInfo != null || artistList != null) {
                        galleryInfo to artistList
                    } else {
                        null
                    }
                } else if (info.pages == 0) {
                    val galleryInfo = find(SPIDER_INFO_FILENAME)?.let {
                        readCompatFromPath(it)?.run {
                            info.pages = pages
                            info.galleryInfo
                        }
                    }

                    galleryInfo?.let { it to null }
                } else {
                    null
                }
            }
        }

        val galleryInfoList = buildList {
            list.forEach { (info, artists) ->
                info?.let {
                    add(it)
                }
                artists?.let { (gid, updateList) ->
                    EhDB.putDownloadArtist(gid, updateList)
                }
            }
        }
        if (galleryInfoList.isNotEmpty()) EhDB.updateGalleryInfo(galleryInfoList)
    }

    private class TaskListener : OnSpiderListener {
        private fun active(event: suspend () -> Unit) = commands.post {
            if (spiderListener === this) event()
        }

        override fun onGetPages(pages: Int) = active {
            mCurrentTask?.let { info ->
                info.total = pages
                mutableNotifyFlow.emit(info)
            } ?: logcat(TAG, LogPriority.ERROR) { "Current task is null, but it should not be" }
        }

        override fun onFatal(error: FatalException) = active {
            mDownloadListener?.onFatal(error)
            stopAllDownloadOwned()
        }

        override fun onPageDownload(
            index: Int,
            contentLength: Long,
            receivedSize: Long,
            bytesRead: Int,
        ) = active {
            mSpeedReminder.onDownload(index, contentLength, receivedSize, bytesRead)
        }

        override fun onPageSuccess(index: Int, finished: Int, downloaded: Int, total: Int) = active {
            mSpeedReminder.onDone(index)
            mCurrentTask?.let { info ->
                info.finished = maxOf(info.finished, finished)
                info.downloaded = maxOf(info.downloaded, downloaded)
                info.total = total
                mDownloadListener?.onDownload(info)
                mutableNotifyFlow.emit(info)
            } ?: logcat(TAG, LogPriority.ERROR) { "Current task is null, but it should not be" }
        }

        override fun onPageFailure(
            index: Int,
            error: String?,
            finished: Int,
            downloaded: Int,
            total: Int,
        ) = active {
            mSpeedReminder.onDone(index)
            mCurrentTask?.let { info ->
                info.finished = maxOf(info.finished, finished)
                info.downloaded = maxOf(info.downloaded, downloaded)
                info.total = total
                mutableNotifyFlow.emit(info)
            } ?: logcat(TAG, LogPriority.ERROR) { "Current task is null, but it should not be" }
        }

        override fun onFinish(finished: Int, downloaded: Int, total: Int, failed: Boolean) = active {
            finishCurrent(finished, downloaded, total, failed)
            ensureDownload()
        }
    }

    private suspend fun finishCurrent(finished: Int, downloaded: Int, total: Int, failed: Boolean) {
        mSpeedReminder.onFinish()
        mCurrentSpider?.let { spider ->
            mCurrentSpider = null
            spiderListener?.let(spider::removeOnSpiderListener)
            SpiderQueen.releaseSpiderQueen(spider, SpiderQueen.MODE_DOWNLOAD)
        }
        spiderListener = null
        mCurrentTask?.let { info ->
            mCurrentTask = null
            mSpeedReminder.stop()
            info.finished = finished
            info.downloaded = downloaded
            info.total = total
            if (!failed && total > 0 && info.pages != total) {
                info.pages = total
                val snapshot = info.galleryInfo.toEntitySnapshot()
                persist { EhDB.putGalleryInfo(snapshot) }
            }
            info.legacy = total - finished
            info.state = if (failed) DownloadInfo.STATE_FAILED else DownloadInfo.STATE_FINISH
            saveStates(listOf(info))
            mDownloadListener?.onFinish(info)
            mutableNotifyFlow.emit(info)
            if (!failed) {
                replaceOldVersionIfRegisteredOwned(info)
            }
        } ?: logcat(TAG, LogPriority.ERROR) { "Current task is null, but it should not be" }
    }

    private suspend fun replaceOldVersionIfRegisteredOwned(newInfo: DownloadInfo) {
        val oldGid = upgradePrefs.getLong(newInfo.gid.toString(), -1L)
        if (oldGid <= 0L || oldGid == newInfo.gid) return
        upgradePrefs.edit().remove(newInfo.gid.toString()).apply()

        persist {
            val oldProgress = EhDB.getReadProgress(oldGid)
            if (oldProgress > 0 && EhDB.getReadProgress(newInfo.gid) <= 0) {
                EhDB.putReadProgress(newInfo.gid, oldProgress)
            }
        }

        stopDownloadInternal(oldGid)
        val oldInfo = mAllInfoMap[oldGid] ?: return
        val preserveDir = newInfo.downloadDir
        removeDownloadEntryOwned(
            info = oldInfo,
            deleteFiles = !oldInfo.isLocal,
            preserveDir = preserveDir,
        )
    }

    private fun GalleryEntity.toEntitySnapshot() = GalleryEntity(
        gid = gid,
        token = token,
        title = title,
        titleJpn = titleJpn,
        thumbKey = thumbKey,
        category = category,
        posted = posted,
        uploader = uploader,
        rating = rating,
        simpleTags = simpleTags?.toList(),
        pages = pages,
        simpleLanguage = simpleLanguage,
        favoriteSlot = favoriteSlot,
    )

    interface DownloadListener {
        /**
         * Fatal error
         */
        fun onFatal(error: FatalException)

        /**
         * Start download
         */
        fun onStart(info: DownloadInfo)

        /**
         * Update download speed
         */
        fun onDownload(info: DownloadInfo)

        /**
         * Download done
         */
        fun onFinish(info: DownloadInfo)

        /**
         * Download done
         */
        fun onCancel(info: DownloadInfo)

        fun onIdle()
    }

    private val mSpeedReminder = object {
        private val contentLengthMap = SparseLongArray()
        private val receivedSizeMap = SparseLongArray()
        private val tracker = SpeedTracker()
        private val mutex = Mutex()
        private var currentJob: Job? = null
        fun start(owner: TaskListener) {
            if (currentJob == null) {
                currentJob = launch {
                    tracker.speedFlow().collect { speed ->
                        commands.post { if (spiderListener === owner) updateSpeed(speed) }
                    }
                }
            }
        }

        suspend fun stop() {
            tracker.reset()
            mutex.withLock {
                contentLengthMap.clear()
                receivedSizeMap.clear()
            }
            currentJob?.cancel()
            currentJob = null
        }

        suspend fun onDownload(index: Int, contentLength: Long, receivedSize: Long, bytesRead: Int) {
            mutex.withLock {
                contentLengthMap.put(index, contentLength)
                receivedSizeMap.put(index, receivedSize)
            }
            tracker.track(bytesRead)
        }

        suspend fun onDone(index: Int) {
            mutex.withLock {
                contentLengthMap.delete(index)
                receivedSizeMap.delete(index)
            }
        }

        suspend fun onFinish() {
            mutex.withLock {
                contentLengthMap.clear()
                receivedSizeMap.clear()
            }
        }

        private suspend fun updateSpeed(speed: Long) {
            mCurrentTask?.let { info ->
                info.speed = speed

                // Calculate remaining
                if (info.total <= 0) {
                    info.remaining = -1
                } else if (speed == 0L) {
                    info.remaining = 300L * 24L * 60L * 60L * 1000L // 300 days
                } else {
                    var downloadingCount = 0
                    var downloadingContentLengthSum = 0L
                    var totalSize = 0L
                    mutex.withLock {
                        for (i in 0..<contentLengthMap.size) {
                            val contentLength = contentLengthMap.valueAt(i)
                            val receivedSize = receivedSizeMap.valueAt(i)
                            if (contentLength <= 0) continue
                            downloadingCount++
                            downloadingContentLengthSum += contentLength
                            totalSize += contentLength - receivedSize
                        }
                    }
                    if (downloadingCount != 0) {
                        totalSize += downloadingContentLengthSum * (info.total - info.downloaded - downloadingCount) / downloadingCount
                        info.remaining = totalSize / speed * 1000
                    } else {
                        info.remaining = -1
                    }
                }
                mDownloadListener?.onDownload(info)
                mutableNotifyFlow.emit(info)
            }
        }
    }

    private const val TAG = "DownloadManager"
}

var downloadLocation: Path
    get() = with(Settings) {
        if (downloadScheme != null) {
            Uri.Builder().apply {
                scheme(downloadScheme)
                encodedAuthority(downloadAuthority)
                encodedPath(downloadPath)
                encodedQuery(downloadQuery)
                encodedFragment(downloadFragment)
            }.build().toOkioPath()
        } else {
            AppConfig.ensureDefaultDownloadDir()?.toOkioPath() ?: "".toPath()
        }
    }
    set(value) = with(value.toUri()) {
        Settings.edit {
            downloadScheme = scheme
            downloadAuthority = encodedAuthority
            downloadPath = encodedPath
            downloadQuery = encodedQuery
            downloadFragment = encodedFragment
        }
    }

/**
 * 校验并尽力修复应用当前的下载目录配置：
 * - 优先直接检测 `downloadLocation.isDirectory`；
 * - 若未配置自定义目录或指向默认下载目录被意外删空，则自动重建默认下载目录并复检；
 * - 若为 SAF `content://` 目录失效但应用已具备所有文件访问权限，尝试解析物理路径并平滑修复为可读写目录。
 */
fun ensureValidDownloadLocation(): Boolean {
    val current = downloadLocation
    if (current.isDirectory) return true

    val defaultFilePath = AppConfig.defaultDownloadDirFile?.absolutePath
    if (Settings.downloadScheme == null || (defaultFilePath != null && current.toString() == defaultFilePath)) {
        AppConfig.ensureDefaultDownloadDir()
        if (downloadLocation.isDirectory) return true
    }

    if (Settings.downloadScheme == ContentResolver.SCHEME_CONTENT && LocalGalleryScanner.hasAllFilesAccess()) {
        val physicalPath = resolveDownloadLocationPath(
            rawLocation = current.toString(),
            externalStorageRoot = Environment.getExternalStorageDirectory().absolutePath,
        )
        val physicalDir = physicalPath?.let(::File)
        if (physicalDir != null && physicalDir.isDirectory && physicalDir.canRead() && physicalDir.canWrite()) {
            downloadLocation = physicalDir.toOkioPath()
            return true
        }
    }

    return false
}

/**
 * 凡以绝对路径（`/` 开头）登记的条目一律视为外部本地只读引用，与可变 UI 标签解耦：
 * 绝不执行主线程阻塞 I/O，也绝不因移动/删除标签导致误删磁盘原文件。
 */
val DownloadInfo.isLocal get() = dirname?.startsWith("/") == true
val DownloadInfo.downloadDir get() = dirname?.let {
    if (it.startsWith("/")) it.toPath() else downloadLocation / it
}

fun archiveThumbPath(gid: Long): Path = AppConfig.tempDir / "archive-thumb-$gid.jpg"

val DownloadInfo.archiveFile get() = downloadDir?.run {
    if (isFile) {
        return@run this
    }
    find("$gid.cbz") ?: find("$gid.zip")
}
val GalleryInfo.tempDownloadDir get() = AppConfig.externalTempPersistDir?.let { it / "$gid" }
