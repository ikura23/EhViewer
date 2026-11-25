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

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.PowerManager
import androidx.collection.LongSparseArray
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.unsafeLazy
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.exception.FatalException
import com.hippo.ehviewer.client.exception.InsufficientGpException
import com.hippo.ehviewer.client.exception.IpBannedException
import com.hippo.ehviewer.client.exception.QuotaExceededException
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.util.ReadableTime
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class DownloadService :
    Service(),
    DownloadManager.DownloadListener,
    CoroutineScope {
    override val coroutineContext = Dispatchers.IO + SupervisorJob()
    private val deferredMgr = async { DownloadManager }
    private val notifyManager by unsafeLazy { NotificationManagerCompat.from(this) }
    private val downloadingNotification by unsafeLazy { initDownloadingNotification() }
    private val downloadedNotification by lazy { initDownloadedNotification() }
    private val fatalNotification by lazy { initFatalNotification() }
    private val channelId by unsafeLazy { "$packageName.download" }

    private var serviceTimedOut = false
    private var foregroundReady = false

    @Volatile
    private var stoppingByUser = false

    @Volatile
    private var inFlightIntent: Intent? = null
    private val stickyWakeup = AtomicReference<CompletableDeferred<Unit>?>(null)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    class ClearReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            clear()
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireDownloadLocks() {
        runCatching {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:download",
            ).apply {
                setReferenceCounted(false)
                acquire()
            }

            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "$packageName:download",
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure {
            logcat(it)
        }
    }

    private fun releaseDownloadLocks() {
        try {
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }.onFailure { logcat(it) }
            runCatching { wifiLock?.takeIf { it.isHeld }?.release() }.onFailure { logcat(it) }
        } finally {
            wakeLock = null
            wifiLock = null
        }
    }

    private val starts = Mutex()
    private val startRequests = Channel<Pair<Int, Intent?>>(Channel.UNLIMITED)
    private var startJob: Job? = null

    @Volatile
    private var latestStartId = 0

    @Volatile
    private var handledStartId = 0

    override fun onTimeout(startId: Int, fgsType: Int) {
        // 触发 dataSync 超时后须尽快退出前台服务，交由恢复状态机保留等待状态。
        serviceTimedOut = true
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        foregroundReady = runCatching {
            notifyManager.createNotificationChannel(
                NotificationChannelCompat.Builder(channelId, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(getString(R.string.download_service)).build(),
            )
            downloadingNotification.builder.apply {
                setContentTitle(getString(R.string.download_service))
                    .setContentText(null)
                    .setSubText(null)
                    .setProgress(0, 0, true)
            }
            startForeground(ID_DOWNLOADING, downloadingNotification.builder.build())
        }.onFailure {
            logcat(it)
        }.isSuccess
        if (!foregroundReady) {
            serviceTimedOut = true
            launch { deferredMgr.await().deferRecoveryUntilForeground() }
            stopSelf()
            return
        }
        acquireDownloadLocks()
        startJob = launch {
            for ((startId, intent) in startRequests) {
                starts.withLock {
                    deferredMgr.await().setDownloadListener(this@DownloadService)
                    inFlightIntent = intent
                    try {
                        handleIntent(intent)
                    } catch (failure: Exception) {
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        logcat(failure)
                    } finally {
                        inFlightIntent = null
                        handledStartId = startId
                        checkStopSelf(startId)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        releaseDownloadLocks()
        // 先关闭接收通道并排空尚未消费的下载请求，防止系统超时或销毁静默丢弃用户新点的下载。
        startRequests.close()
        stickyWakeup.getAndSet(null)?.complete(Unit)
        val pendingStarts = mutableListOf<Intent>()
        inFlightIntent?.takeIf { isStartIntent(it) }?.let { pendingStarts += it }
        while (true) {
            val (_, queued) = startRequests.tryReceive().getOrNull() ?: break
            if (isStartIntent(queued)) pendingStarts += queued!!
        }
        val worker = startJob
        worker?.cancel()
        val userStopped = stoppingByUser
        if (!userStopped && pendingStarts.isNotEmpty()) {
            pendingStarts.forEach { persistDeferredIntent(it, deferRecovery = true) }
        }
        val scope = this
        launch {
            worker?.cancelAndJoin()
            // 确保串行延迟队列中的暂停或新下载全部落盘后再解除监听。
            runCatching { deferredCommands.execute {} }
            val manager = deferredMgr.await()
            manager.detachDownloadListener(this@DownloadService, serviceTimedOut && !userStopped)
            delay(DELAY)
            scope.cancel()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLEAR) {
            clear()
            if (!foregroundReady || serviceTimedOut) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_ALL) {
            // 用户主动全部暂停：不记录 waiting_for_foreground，经串行队列与 onDestroy 同步顺序。
            stoppingByUser = true
            stickyWakeup.getAndSet(null)?.complete(Unit)
            persistDeferredIntent(intent, deferRecovery = false)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!foregroundReady || serviceTimedOut) {
            persistDeferredIntent(intent, deferRecovery = isStartIntent(intent))
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent != null) {
            stoppingByUser = false
            stickyWakeup.getAndSet(null)?.complete(Unit)
        }
        latestStartId = startId
        startRequests.trySend(startId to intent)
        return START_STICKY
    }

    private suspend fun handleIntent(intent: Intent?) {
        if (intent == null) {
            val wait = deferredMgr.await().stickyRecoveryDelay() ?: return
            if (wait > 0L) {
                val signal = CompletableDeferred<Unit>()
                stickyWakeup.set(signal)
                try {
                    withTimeoutOrNull(wait) { signal.await() }
                } finally {
                    stickyWakeup.compareAndSet(signal, null)
                }
            }
            deferredMgr.await().resumeInterruptedDownloads(this)
            return
        }
        if (intent.action == ACTION_START) {
            dispatchIntent(intent)
            inFlightIntent = null
            deferredMgr.await().resumeInterruptedDownloads(this)
            return
        }
        if (intent.action in listOf(ACTION_RESUME, ACTION_START_RANGE, ACTION_START_ALL)) {
            deferredMgr.await().resumeInterruptedDownloads(this)
        }
        dispatchIntent(intent)
        inFlightIntent = null
    }

    override fun onBind(intent: Intent) = null

    private fun initDownloadingNotification(): NotificationHandler {
        val stopAllIntent = Intent(this, DownloadService::class.java)
        stopAllIntent.action = ACTION_STOP_ALL
        val piStopAll =
            PendingIntent.getService(this, 0, stopAllIntent, PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(
                com.hippo.ehviewer.R.drawable.v_pause_x24,
                getString(R.string.stat_download_action_stop_all),
                piStopAll,
            )
            .setShowWhen(false)
        return NotificationHandler(this, notifyManager, builder, ID_DOWNLOADING)
            .apply { launch { run() } }
    }

    private fun initDownloadedNotification(): NotificationHandler {
        val clearIntent = Intent(this, ClearReceiver::class.java)
        val piClear = PendingIntent.getBroadcast(this, 0, clearIntent, PendingIntent.FLAG_IMMUTABLE)
        val bundle = Bundle()
        bundle.putString(KEY_ACTION, ACTION_CLEAR_DOWNLOAD_SERVICE)
        val activityIntent = Intent(this, MainActivity::class.java)
        activityIntent.action = ACTION_START_DOWNLOADSCENE
        activityIntent.putExtra(ACTION_START_DOWNLOADSCENE_ARGS, bundle)
        val piActivity = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.stat_download_done_title))
            .setDeleteIntent(piClear)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(piActivity)
        return NotificationHandler(this, notifyManager, builder, ID_DOWNLOADED)
            .apply { launch { run() } }
    }

    private fun initFatalNotification(): NotificationHandler {
        val builder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(com.hippo.ehviewer.R.drawable.ic_baseline_warning_24)
            .setAutoCancel(true)
            .setOngoing(false)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
        return NotificationHandler(this, notifyManager, builder, ID_FATAL)
            .apply { launch { run() } }
    }

    override fun onFatal(error: FatalException) {
        val (title, text) = when (error) {
            is IpBannedException -> R.string.error_ip_banned to error.message
            is InsufficientGpException -> R.string.insufficient_funds to error.message
            is QuotaExceededException -> R.string.stat_509_alert_title to getString(R.string.stat_509_alert_text)
        }
        fatalNotification.run {
            builder.setWhen(System.currentTimeMillis())
                .setContentTitle(getString(title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            show()
        }
    }

    override fun onStart(info: DownloadInfo) {
        val bundle = Bundle()
        bundle.putLong(KEY_GID, info.gid)
        val activityIntent = Intent(this, MainActivity::class.java)
        activityIntent.action = ACTION_START_DOWNLOADSCENE
        activityIntent.putExtra(ACTION_START_DOWNLOADSCENE_ARGS, bundle)
        val piActivity = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        downloadingNotification.run {
            builder.setContentTitle(EhUtils.getSuitableTitle(info))
                .setContentText(null)
                .setSubText(null)
                .setStyle(null)
                .setProgress(0, 0, true)
                .setContentIntent(piActivity)
            show()
        }
    }

    override fun onDownload(info: DownloadInfo) {
        val speed = info.speed.coerceAtLeast(0)
        val speedText = FileUtils.humanReadableByteCount(speed) + "/s"
        val remaining = info.remaining
        val text = if (remaining >= 0) {
            val interval = ReadableTime.getShortTimeInterval(remaining)
            getString(R.string.download_speed_text_2, speedText, interval)
        } else {
            getString(R.string.download_speed_text, speedText)
        }
        downloadingNotification.run {
            builder.setContentTitle(EhUtils.getSuitableTitle(info))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSubText(if (info.total == -1 || info.finished == -1) null else info.finished.toString() + "/" + info.total)
                .setProgress(info.total, info.finished, false)
            show()
        }
    }

    override fun onFinish(info: DownloadInfo) {
        downloadingNotification.cancel()
        val finish = info.state == DownloadInfo.STATE_FINISH
        val gid = info.gid
        val (text, style, downloadedCount) = synchronized(Companion) {
            val index = sItemStateArray.indexOfKey(gid)
            if (index < 0) {
                sItemStateArray.put(gid, finish)
                sItemTitleArray.put(gid, EhUtils.getSuitableTitle(info))
                sDownloadedCount++
                if (finish) {
                    sFinishedCount++
                } else {
                    sFailedCount++
                }
            } else {
                val oldFinish = sItemStateArray.valueAt(index)
                sItemStateArray.put(gid, finish)
                sItemTitleArray.put(gid, EhUtils.getSuitableTitle(info))
                if (oldFinish && !finish) {
                    sFinishedCount--
                    sFailedCount++
                } else if (!oldFinish && finish) {
                    sFinishedCount++
                    sFailedCount--
                }
            }
            val summaryText: String
            val needStyle: Boolean
            if (sFinishedCount != 0 && sFailedCount == 0) {
                if (sFinishedCount == 1) {
                    summaryText = if (sItemTitleArray.size() >= 1) {
                        getString(
                            R.string.stat_download_done_line_succeeded,
                            sItemTitleArray.valueAt(0),
                        )
                    } else {
                        logcat { "WTF, sItemTitleArray is null" }
                        getString(R.string.error_unknown)
                    }
                    needStyle = false
                } else {
                    summaryText = getString(R.string.stat_download_done_text_succeeded, sFinishedCount)
                    needStyle = true
                }
            } else if (sFinishedCount == 0 && sFailedCount != 0) {
                if (sFailedCount == 1) {
                    summaryText = if (sItemTitleArray.size() >= 1) {
                        getString(
                            R.string.stat_download_done_line_failed,
                            sItemTitleArray.valueAt(0),
                        )
                    } else {
                        logcat { "WTF, sItemTitleArray is null" }
                        getString(R.string.error_unknown)
                    }
                    needStyle = false
                } else {
                    summaryText = getString(R.string.stat_download_done_text_failed, sFailedCount)
                    needStyle = true
                }
            } else {
                summaryText = getString(R.string.stat_download_done_text_mix, sFinishedCount, sFailedCount)
                needStyle = true
            }
            val inboxStyle: NotificationCompat.InboxStyle? = if (needStyle) {
                NotificationCompat.InboxStyle().also { inbox ->
                    inbox.setBigContentTitle(getString(R.string.stat_download_done_title))
                    val stateArray = sItemStateArray
                    var i = 0
                    val n = stateArray.size()
                    while (i < n) {
                        val id = stateArray.keyAt(i)
                        val fin = stateArray.valueAt(i)
                        val title = sItemTitleArray[id]
                        if (title != null) {
                            inbox.addLine(
                                getString(
                                    if (fin) R.string.stat_download_done_line_succeeded else R.string.stat_download_done_line_failed,
                                    title,
                                ),
                            )
                        }
                        i++
                    }
                }
            } else {
                null
            }
            Triple(summaryText, inboxStyle, sDownloadedCount)
        }
        downloadedNotification.run {
            builder.setContentText(text)
                .setStyle(style)
                .setWhen(System.currentTimeMillis())
                .setNumber(downloadedCount)
            show()
        }
    }

    override fun onCancel(info: DownloadInfo) {
        downloadingNotification.cancel()
    }

    override fun onIdle() {
        val startId = handledStartId
        launch { starts.withLock { checkStopSelf(startId) } }
    }

    private suspend fun checkStopSelf(startId: Int) {
        if (startId == 0 || startId != latestStartId) return
        deferredMgr.await().stopServiceIfIdle(this) {
            stopSelfResult(startId)
        }
    }

    private class NotificationHandler(
        private val service: Service,
        private val notifyManager: NotificationManagerCompat,
        val builder: NotificationCompat.Builder,
        private val id: Int,
    ) {
        private val channel = Channel<Ops>(Channel.CONFLATED)

        /** 在调用方线程同步生成不可变 Notification 快照，避免跨线程并发读写非线程安全的 Builder。 */
        fun show() {
            val snapshot = builder.build()
            channel.trySend(Ops.Notify(snapshot))
        }

        fun cancel() {
            channel.trySend(Ops.Cancel)
        }

        suspend fun run() {
            while (true) {
                when (val op = channel.receive()) {
                    is Ops.Notify -> {
                        if (ActivityCompat.checkSelfPermission(
                                service,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            notifyManager.notify(id, op.notification)
                        }
                    }
                    Ops.Cancel -> notifyManager.cancel(id)
                }
                delay(DELAY)
            }
        }

        private sealed interface Ops {
            class Notify(val notification: Notification) : Ops
            data object Cancel : Ops
        }
    }

    companion object {
        const val ACTION_START_DOWNLOADSCENE = "start_download_scene"
        const val ACTION_START_DOWNLOADSCENE_ARGS = "start_download_scene_args"

        internal const val ACTION_RESUME = "resume_interrupted"
        const val ACTION_START = "start"
        const val ACTION_START_RANGE = "start_range"
        const val ACTION_START_ALL = "start_all"
        const val ACTION_STOP_ALL = "stop_all"
        const val ACTION_CLEAR = "clear"
        const val KEY_GALLERY_INFO = "gallery_info"
        const val KEY_LABEL = "label"
        const val KEY_GID = "gid"
        const val KEY_GID_LIST = "gid_list"
        const val KEY_ACTION = "action"
        const val ACTION_CLEAR_DOWNLOAD_SERVICE = "clear_download_service"
        private const val ID_DOWNLOADING = 1
        private const val ID_DOWNLOADED = 2
        private const val ID_FATAL = 3
        private const val DELAY = 1000L // 1s
        private val sItemStateArray = LongSparseArray<Boolean>()
        private val sItemTitleArray = LongSparseArray<String>()
        private var sFailedCount = 0
        private var sFinishedCount = 0
        private var sDownloadedCount = 0

        private val requestScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val deferredCommands = DownloadCommands(requestScope) { logcat(it) }

        private fun isStartIntent(intent: Intent?): Boolean = intent?.action in listOf(
            ACTION_START,
            ACTION_START_RANGE,
            ACTION_START_ALL,
            ACTION_RESUME,
        )

        private fun persistDeferredIntent(intent: Intent?, deferRecovery: Boolean = isStartIntent(intent)) {
            deferredCommands.post {
                runCatching {
                    dispatchIntent(intent, deferred = true)
                    if (deferRecovery) {
                        DownloadManager.deferRecoveryUntilForeground()
                    }
                }.onFailure { logcat(it) }
            }
        }

        private suspend fun dispatchIntent(intent: Intent?, deferred: Boolean = false) {
            when (intent?.action) {
                ACTION_START -> {
                    val gi = intent.getBundleExtra(KEY_GALLERY_INFO) ?: return
                    DownloadManager.startDownload(decodeFromSavedState(gi), intent.getStringExtra(KEY_LABEL), deferred)
                }
                ACTION_START_RANGE -> intent.getLongArrayExtra(KEY_GID_LIST)?.let {
                    DownloadManager.startRangeDownload(it, deferred)
                }
                ACTION_START_ALL -> DownloadManager.startAllDownload(deferred)
                ACTION_STOP_ALL -> DownloadManager.stopAllDownload()
                ACTION_CLEAR -> clear()
            }
        }

        context(ctx: Context)
        fun startService(action: String, intentBuilder: Intent.() -> Unit = {}) {
            val intent = Intent(ctx, DownloadService::class.java).setAction(action).apply(intentBuilder)
            runCatching { ContextCompat.startForegroundService(ctx, intent) }.onFailure {
                ctx.logcat(it)
                persistDeferredIntent(intent, deferRecovery = isStartIntent(intent))
            }
        }

        context(_: Context)
        fun startDownload(info: BaseGalleryInfo, label: String? = null) = startService(ACTION_START) {
            putExtra(KEY_GALLERY_INFO, encodeToSavedState(info))
            label?.let { putExtra(KEY_LABEL, it) }
        }

        context(_: Context)
        fun startRangeDownload(gidList: LongArray) = startService(ACTION_START_RANGE) {
            putExtra(KEY_GID_LIST, gidList)
        }

        fun clear() = synchronized(this) {
            sFailedCount = 0
            sFinishedCount = 0
            sDownloadedCount = 0
            sItemStateArray.clear()
            sItemTitleArray.clear()
        }
    }
}
