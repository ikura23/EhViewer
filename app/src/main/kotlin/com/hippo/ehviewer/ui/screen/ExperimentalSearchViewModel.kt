package com.hippo.ehviewer.ui.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.imageLoader
import coil3.request.ImageRequest
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.coil.ehUrl
import com.hippo.ehviewer.ui.experimental.ExperimentalRecommendationCache
import com.hippo.ehviewer.ui.experimental.RecommendTabMode
import com.hippo.ehviewer.ui.experimental.RecommendedTagItem
import com.hippo.ehviewer.ui.experimental.extractRecommendedTags
import com.hippo.ehviewer.ui.experimental.fetchHighRatedGalleryBatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import splitties.init.appCtx

data class TabRecommendUiState(
    val items: List<RecommendedTagItem> = emptyList(),
    val isInitialLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val errorMessage: String? = null,
    val nonHMode: Boolean = false,
    val site: Int = -1,
)

private data class TabCursorSession(
    var initialized: Boolean = false,
    var nonHMode: Boolean = false,
    var site: Int = -1,
    var sourceSeed: Int = 0,
    var nextCursor: String? = null,
    var nextToplistPage: Int = 2,
    var usedToplist: Boolean = false,
    var canFallbackNormal: Boolean = false,
    val seenTagKeys: ArrayDeque<String> = ArrayDeque(),
    val usedCoverGids: HashSet<Long> = HashSet(),
    val usedCoverThumbKeys: HashSet<String> = HashSet(),
) {
    fun trimSeenTagsIfNeeded(maxHistory: Int = 180) {
        while (seenTagKeys.size > maxHistory) {
            repeat((maxHistory / 2).coerceAtMost(seenTagKeys.size)) {
                seenTagKeys.removeFirstOrNull()
            }
        }
    }

    fun resetForNewMode(nonHMode: Boolean, site: Int) {
        initialized = false
        this.nonHMode = nonHMode
        this.site = site
        sourceSeed = 0
        nextCursor = null
        nextToplistPage = 2
        usedToplist = false
        canFallbackNormal = false
        seenTagKeys.clear()
        usedCoverGids.clear()
        usedCoverThumbKeys.clear()
    }
}

/**
 * 实验性新版搜索页推荐状态管理：负责多源真实高星标签抽取、会话级去重、下拉刷新换批、触底无限加载与首屏封面预取。
 */
class ExperimentalSearchViewModel : ViewModel() {

    private val stateFlows = RecommendTabMode.entries.associateWith {
        MutableStateFlow(TabRecommendUiState())
    }
    private val sessions = RecommendTabMode.entries.associateWith { TabCursorSession() }
    private val activeJobs = HashMap<RecommendTabMode, Job>()

    fun uiStateFlow(tabMode: RecommendTabMode): StateFlow<TabRecommendUiState> =
        stateFlows.getValue(tabMode).asStateFlow()

    fun ensureLoaded(tabMode: RecommendTabMode, nonHMode: Boolean, site: Int) {
        val session = sessions.getValue(tabMode)
        val modeChanged = session.nonHMode != nonHMode || session.site != site
        if (session.initialized && !modeChanged && stateFlows.getValue(tabMode).value.items.isNotEmpty()) {
            return
        }
        if (modeChanged) {
            activeJobs[tabMode]?.cancel()
            session.resetForNewMode(nonHMode, site)
            stateFlows.getValue(tabMode).value = TabRecommendUiState(
                isInitialLoading = true,
                nonHMode = nonHMode,
                site = site,
            )
        } else if (stateFlows.getValue(tabMode).value.isInitialLoading && activeJobs[tabMode]?.isActive == true) {
            return
        }

        session.initialized = true
        activeJobs[tabMode]?.cancel()
        activeJobs[tabMode] = viewModelScope.launch {
            // 先读轻量磁盘缓存，命中即秒出；如果缓存已过期再后台静默拉取最新高星批次
            val cached = ExperimentalRecommendationCache.load(appCtx, site, nonHMode, tabMode)
            if (cached != null) {
                val (cachedItems, isFresh) = cached
                cachedItems.forEach { item ->
                    if (item.tagKey !in session.seenTagKeys) session.seenTagKeys.addLast(item.tagKey)
                    session.usedCoverGids.add(item.coverGallery.gid)
                    item.coverGallery.thumbKey?.let { session.usedCoverThumbKeys.add(it) }
                }
                stateFlows.getValue(tabMode).update {
                    it.copy(
                        items = cachedItems,
                        isInitialLoading = false,
                        errorMessage = null,
                        nonHMode = nonHMode,
                        site = site,
                    )
                }
                prefetchTopCovers(cachedItems)
                if (isFresh) return@launch
            }
            fetchBatch(tabMode = tabMode, nonHMode = nonHMode, site = site, isRefresh = false, isLoadMore = false)
        }
    }

    fun refresh(tabMode: RecommendTabMode, nonHMode: Boolean, site: Int) {
        activeJobs[tabMode]?.cancel()
        val session = sessions.getValue(tabMode)
        if (session.nonHMode != nonHMode || session.site != site) {
            session.resetForNewMode(nonHMode, site)
        }
        session.initialized = true
        session.sourceSeed += 1
        session.nextCursor = null
        session.nextToplistPage = 2
        session.usedToplist = false
        session.canFallbackNormal = false
        // 刷新保留最近 80 个已推荐 tagKey，保证尽量不与上一轮重复，同时保留长期池循环空间
        while (session.seenTagKeys.size > 80) {
            session.seenTagKeys.removeFirstOrNull()
        }
        session.usedCoverGids.clear()
        session.usedCoverThumbKeys.clear()

        stateFlows.getValue(tabMode).update {
            it.copy(isRefreshing = true, errorMessage = null, nonHMode = nonHMode, site = site)
        }
        activeJobs[tabMode] = viewModelScope.launch {
            fetchBatch(tabMode = tabMode, nonHMode = nonHMode, site = site, isRefresh = true, isLoadMore = false)
        }
    }

    fun loadMore(tabMode: RecommendTabMode, nonHMode: Boolean, site: Int) {
        val current = stateFlows.getValue(tabMode).value
        if (current.isInitialLoading || current.isRefreshing || current.isLoadingMore || !current.hasMore) {
            return
        }
        val session = sessions.getValue(tabMode)
        if (session.nonHMode != nonHMode || session.site != site) {
            ensureLoaded(tabMode, nonHMode, site)
            return
        }
        stateFlows.getValue(tabMode).update { it.copy(isLoadingMore = true, errorMessage = null) }
        activeJobs[tabMode] = viewModelScope.launch {
            fetchBatch(tabMode = tabMode, nonHMode = nonHMode, site = site, isRefresh = false, isLoadMore = true)
        }
    }

    private suspend fun fetchBatch(
        tabMode: RecommendTabMode,
        nonHMode: Boolean,
        site: Int,
        isRefresh: Boolean,
        isLoadMore: Boolean,
    ) {
        val session = sessions.getValue(tabMode)
        val flow = stateFlows.getValue(tabMode)
        val batchSizeTarget = if (isLoadMore) 12 else 16

        if (!isLoadMore && !isRefresh && flow.value.items.isEmpty()) {
            flow.update { it.copy(isInitialLoading = true, errorMessage = null) }
        }

        try {
            val collectedItems = mutableListOf<RecommendedTagItem>()
            val excludeSet = session.seenTagKeys.toMutableSet()
            var hasMoreResult = true
            var attempts = 0
            var currentLoadMoreFlag = isLoadMore

            // 严格封面不重复下，若单页新产出不足 10 条且仍有下一页，最多自动续拉 3 页补充
            while (collectedItems.size < 10 && attempts < 3 && hasMoreResult) {
                attempts += 1
                val fetchResult = fetchHighRatedGalleryBatch(
                    sourceSeed = session.sourceSeed,
                    nextCursor = session.nextCursor,
                    nextToplistPage = session.nextToplistPage,
                    usedToplist = session.usedToplist,
                    canFallbackNormal = session.canFallbackNormal,
                    nonHMode = nonHMode,
                    isLoadMore = currentLoadMoreFlag,
                )
                session.nextCursor = fetchResult.nextCursor
                session.nextToplistPage = fetchResult.nextToplistPage
                session.usedToplist = fetchResult.usedToplist
                session.canFallbackNormal = fetchResult.canFallbackNormal
                hasMoreResult = fetchResult.hasMore

                val needCount = (batchSizeTarget - collectedItems.size).coerceAtLeast(1)
                val extracted = extractRecommendedTags(
                    galleries = fetchResult.galleries,
                    tabMode = tabMode,
                    nonHMode = nonHMode,
                    excludeTagKeys = excludeSet,
                    usedCoverGids = session.usedCoverGids,
                    usedCoverThumbKeys = session.usedCoverThumbKeys,
                    maxItems = needCount,
                    translateTag = { nsPrefix, rawTag ->
                        if (EhTagDatabase.initialized) {
                            EhTagDatabase.getTranslation(nsPrefix, rawTag)
                        } else {
                            null
                        }
                    },
                )
                collectedItems.addAll(extracted.items)
                excludeSet.addAll(extracted.addedTagKeys)
                extracted.addedTagKeys.forEach { key ->
                    if (key !in session.seenTagKeys) session.seenTagKeys.addLast(key)
                }
                session.usedCoverGids.addAll(extracted.addedCoverGids)
                session.usedCoverThumbKeys.addAll(extracted.addedCoverThumbKeys)
                session.trimSeenTagsIfNeeded()

                if (!hasMoreResult || fetchResult.galleries.isEmpty()) break
                currentLoadMoreFlag = true
            }

            // 如果刷新时因历史去重太严格导致极少或空，放开历史除重兜底提取一轮确保画面不空
            if (isRefresh && collectedItems.isEmpty()) {
                session.seenTagKeys.clear()
                session.usedCoverGids.clear()
                session.usedCoverThumbKeys.clear()
                val retryFetch = fetchHighRatedGalleryBatch(
                    sourceSeed = session.sourceSeed + 1,
                    nextCursor = null,
                    nextToplistPage = 2,
                    usedToplist = false,
                    canFallbackNormal = false,
                    nonHMode = nonHMode,
                    isLoadMore = false,
                )
                session.nextCursor = retryFetch.nextCursor
                session.nextToplistPage = retryFetch.nextToplistPage
                session.usedToplist = retryFetch.usedToplist
                session.canFallbackNormal = retryFetch.canFallbackNormal
                hasMoreResult = retryFetch.hasMore

                val fallback = extractRecommendedTags(
                    galleries = retryFetch.galleries,
                    tabMode = tabMode,
                    nonHMode = nonHMode,
                    excludeTagKeys = emptySet(),
                    usedCoverGids = emptySet(),
                    usedCoverThumbKeys = emptySet(),
                    maxItems = batchSizeTarget,
                    translateTag = { nsPrefix, rawTag ->
                        if (EhTagDatabase.initialized) EhTagDatabase.getTranslation(nsPrefix, rawTag) else null
                    },
                )
                collectedItems.addAll(fallback.items)
                fallback.addedTagKeys.forEach { session.seenTagKeys.addLast(it) }
                session.usedCoverGids.addAll(fallback.addedCoverGids)
                session.usedCoverThumbKeys.addAll(fallback.addedCoverThumbKeys)
            }

            val merged = if (isLoadMore) {
                (flow.value.items + collectedItems).distinctBy { it.tagKey }
            } else {
                collectedItems
            }

            flow.update {
                it.copy(
                    items = merged,
                    isInitialLoading = false,
                    isRefreshing = false,
                    isLoadingMore = false,
                    hasMore = hasMoreResult,
                    errorMessage = null,
                    nonHMode = nonHMode,
                    site = site,
                )
            }

            if (merged.isNotEmpty()) {
                if (!isLoadMore) {
                    ExperimentalRecommendationCache.save(appCtx, site, nonHMode, tabMode, merged)
                    prefetchTopCovers(merged)
                } else {
                    prefetchTopCovers(collectedItems)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            flow.update {
                it.copy(
                    isInitialLoading = false,
                    isRefreshing = false,
                    isLoadingMore = false,
                    errorMessage = e.message ?: "Load failed",
                )
            }
        }
    }

    /**
     * 低并发分批预热前 10 张封面进 Coil 磁盘与内存缓存，避免并发打满连接池反而拖慢首图。
     */
    private fun prefetchTopCovers(items: List<RecommendedTagItem>) {
        val targets = items.take(10)
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val loader = appCtx.imageLoader
            targets.chunked(3).forEach { chunk ->
                coroutineScope {
                    chunk.map { item ->
                        async {
                            runCatching {
                                val req = ImageRequest.Builder(appCtx)
                                    .ehUrl(item.coverGallery)
                                    .build()
                                loader.execute(req)
                            }
                        }
                    }.awaitAll()
                }
            }
        }
    }
}
