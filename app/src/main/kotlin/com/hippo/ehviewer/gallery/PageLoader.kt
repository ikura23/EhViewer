package com.hippo.ehviewer.gallery

import androidx.collection.SieveCache
import androidx.collection.mutableIntObjectMapOf
import arrow.fx.coroutines.ExitCase
import arrow.fx.coroutines.bracketCase
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.isAtLeastO
import com.ehviewer.core.util.withNonCancellableContext
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.image.Image
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.util.OSUtils
import com.hippo.ehviewer.util.detectAds
import com.hippo.ehviewer.util.displayString
import java.util.WeakHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import moe.tarsin.coroutines.NamedMutex
import moe.tarsin.coroutines.withLock
import okio.Path

private val progressScope = CoroutineScope(Dispatchers.IO)
private const val MAX_CACHE_SIZE = 512 * 1024 * 1024
private const val MIN_CACHE_SIZE = 256 * 1024 * 1024

private val activeLoaders = WeakHashMap<PageLoader, Unit>()

internal fun trimReaderMemory() {
    val loaders = synchronized(activeLoaders) { activeLoaders.keys.toList() }
    loaders.forEach { it.trimIdleImages() }
}

abstract class PageLoader(
    val scope: CoroutineScope,
    val info: GalleryInfo?,
    startPage: Int,
    val size: Int,
    val hasAds: Boolean = false,
    cacheSize: Int? = null,
    decodeParallelism: Int = 4,
) : AutoCloseable {
    var startPage = startPage.coerceIn(0, size - 1)

    private val jobs = mutableIntObjectMapOf<Job>()
    private val mutex = NamedMutex<Int>()
    private val semaphore = Semaphore(decodeParallelism)

    private val cache = SieveCache<Int, Image>(
        maxSize = cacheSize ?: if (isAtLeastO) {
            (OSUtils.totalMemory / 8).toInt().coerceIn(MIN_CACHE_SIZE, MAX_CACHE_SIZE)
        } else {
            (OSUtils.appMaxMemory / 3 * 2).toInt()
        },
        sizeOf = { _, v -> v.allocationSize.toInt() },
        onEntryRemoved = { k, o, n, _ -> if (o.unpin()) n ?: notifyPageWait(k) },
    )

    private suspend fun atomicallyDecodeAndUpdate(index: Int) {
        bracketCase(
            { openSource(index) },
            { src ->
                withNonCancellableContext {
                    notifyPageSucceed(index, Image.decode(src, hasAds && detectAds(index, size)))
                }
            },
            { src, case -> if (case !is ExitCase.Completed) src.close() },
        )
    }

    private val lock = ReentrantReadWriteLock()

    val pages = (0 until size).map { Page(it) }

    var extraPrefetchCount = 0

    private val prefetchPageCount
        get() = maxOf(Settings.preloadImage.value, extraPrefetchCount)

    fun restart() {
        setHotRange(null)
        lock.write { cache.evictAll() }
        pages.forEach(Page::reset)
    }

    private val prevIndex = AtomicInt(-1)

    init {
        synchronized(activeLoaders) { activeLoaders[this] = Unit }
    }

    internal fun trimIdleImages() = lock.write {
        val current = prevIndex.load()
        val candidates = mutableListOf<Int>()
        cache.forEach { index, image ->
            if (index != current && image.refcnt.load() == 1) candidates += index
        }
        candidates.forEach { cache.remove(it) }
    }

    fun retryPage(index: Int, orgImg: Boolean = false) {
        synchronized(this) {
            hotImages.remove(index)?.unpin()
        }
        notifyPageWait(index)
        lock.write { cache.remove(index) }
        onRequest(index, true, orgImg)
    }

    protected abstract fun prefetchPages(pages: List<Int>, bounds: IntRange)

    protected abstract fun onRequest(index: Int, force: Boolean = false, orgImg: Boolean = false)

    fun notifyPageWait(index: Int) {
        pages[index].reset()
    }

    fun notifyPagePercent(index: Int, percent: Float) {
        pages[index].statusFlow.update {
            when (it) {
                is PageStatus.Loading -> it.apply { progress.update { percent } }
                else -> PageStatus.Loading(MutableStateFlow(percent))
            }
        }
    }

    @Synchronized
    fun notifyPageSucceed(index: Int, image: Image, replaceCache: Boolean = true) {
        if (hotRange?.contains(index) == true) pinHot(index, image)
        if (replaceCache) lock.write { cache[index] = image }
        // 避免单图超过 cacheSize 被立刻淘汰并回收后，仍将死图发布给界面导致永久白屏。
        if (image.refcnt.load() <= 0) {
            notifyPageFailed(index, null)
            return
        }
        pages[index].statusFlow.update { if (image.hasQrCode) PageStatus.Blocked(image) else PageStatus.Ready(image) }
    }

    private var hotRange: IntRange? = null

    private val hotImages = HashMap<Int, Image>()

    private fun peekHot(index: Int): Image? = synchronized(this) { hotImages[index] }

    /**
     * 只读探测当前内存中已解码的页面图像，不触发任何加载或网络请求。
     */
    fun peekCachedImage(index: Int): Image? {
        if (index !in 0 until size) return null
        return peekHot(index) ?: lock.read { cache[index] }
    }

    /**
     * 打开本地文件或磁盘缓存中的只读预览源；未缓存或已关闭时返回 `null`，绝不发起网络请求。
     */
    open fun openCachedPreviewSource(index: Int): ImageSource? = null

    private fun pinHot(index: Int, image: Image) {
        val existing = hotImages[index]
        if (existing === image) return
        if (existing != null) {
            if (existing.unpin() && (pages[index].status as? PageStatus.Ready)?.image === existing) {
                pages[index].reset()
            }
        }
        if (image.pin()) hotImages[index] = image else hotImages.remove(index)
    }

    /**
     * Pin the pages that are about to be displayed so cache eviction can never drop them
     * between decode and display. That eviction is what caused the visible loading blink
     * when the next animation was handed over.
     */
    @Synchronized
    fun setHotRange(range: IntRange?) {
        hotRange = range
        if (range == null) {
            hotImages.forEach { (index, image) ->
                if (image.unpin() && (pages[index].status as? PageStatus.Ready)?.image === image) {
                    pages[index].reset()
                }
            }
            hotImages.clear()
            return
        }
        val iterator = hotImages.entries.iterator()
        while (iterator.hasNext()) {
            val (index, image) = iterator.next()
            if (index in range) continue
            iterator.remove()
            if (image.unpin() && (pages[index].status as? PageStatus.Ready)?.image === image) {
                pages[index].reset()
            }
        }
        for (index in range) {
            if (hotImages.containsKey(index)) continue
            val image = lock.read { cache[index] } ?: continue
            if (image.pin()) hotImages[index] = image
        }
    }

    fun notifyPageFailed(index: Int, error: String?) {
        pages[index].statusFlow.update { PageStatus.Error(error) }
    }

    override fun close() {
        synchronized(activeLoaders) { activeLoaders.remove(this) }
        setHotRange(null)
        lock.write { cache.evictAll() }
        info?.run {
            progressScope.launch {
                EhDB.putReadProgress(gid, startPage)
            }
        }
    }

    abstract val title: String

    protected abstract fun getImageExtension(index: Int): String?

    fun imageExtension(index: Int): String? = getImageExtension(index)

    fun getImageFilename(index: Int): String? = getImageExtension(index)?.let {
        FileUtils.sanitizeFilename("$title - ${index + 1}.${it.lowercase()}")
    }

    fun request(index: Int) {
        val prefetchRange = if (index >= prevIndex.load()) {
            index + 1..(index + prefetchPageCount).coerceAtMost(size - 1)
        } else {
            index - 1 downTo (index - prefetchPageCount).coerceAtLeast(0)
        }
        prevIndex.store(index)
        // A pinned hot page stays alive even when the size-bounded cache drops its entry;
        // resolving it here prevents a needless re-decode and the loading blink that follows.
        val image = peekHot(index) ?: lock.read { cache[index] }
        if (image != null) {
            notifyPageSucceed(index, image, false)
        } else {
            notifyPageWait(index)
            onRequest(index)
        }

        // Prefetch to disk
        val pagesAbsent = prefetchRange.filter {
            when (pages[it].status) {
                PageStatus.Queued, is PageStatus.Error -> true
                else -> false
            }
        }
        val start = minOf(index, if (prefetchRange.step > 0) prefetchRange.first else prefetchRange.last)
        val end = maxOf(index, if (prefetchRange.step > 0) prefetchRange.last else prefetchRange.first)
        prefetchPages(pagesAbsent, start - 5..end + 5)
    }

    fun cancelRequest(index: Int) {
        synchronized(jobs) { jobs.remove(index) }?.cancel()
    }

    /** Keep only the current page, its forward preload window, and one back page in memory. */
    fun trimToWindow(current: Int, forwardCount: Int) {
        val first = (current - 1).coerceAtLeast(0)
        val last = (current + forwardCount).coerceAtMost(size - 1)
        for (index in 0 until size) {
            if (index in first..last) continue
            cancelRequest(index)
            lock.write { cache.remove(index) }
            if (pages[index].status !is PageStatus.Queued) pages[index].reset()
        }
    }

    fun requestRange(start: Int, end: Int) {
        if (start > end) return
        for (index in start..end) {
            request(index)
        }
    }

    abstract fun save(index: Int, file: Path): Boolean

    fun notifySourceReady(index: Int) = synchronized(jobs) {
        if (jobs[index]?.isActive != true) {
            jobs[index] = scope.launch {
                try {
                    mutex.withLock(index) {
                        semaphore.withPermit {
                            atomicallyDecodeAndUpdate(index)
                        }
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) {
                        // 解码任务被滑走取消时恢复为等待转圈状态，不显示报错面板
                        notifyPageWait(index)
                        throw e
                    } else {
                        notifyPageFailed(index, e.displayString())
                    }
                } finally {
                    synchronized(jobs) { jobs.remove(index) }
                }
            }
        }
    }

    abstract fun openSource(index: Int): ImageSource
}
