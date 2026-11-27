package com.hippo.ehviewer.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.gallery.PageLoader
import com.hippo.ehviewer.gallery.PageStatus
import com.hippo.ehviewer.gallery.status
import com.hippo.ehviewer.gallery.statusObserved
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Video-like playback for galleries whose pages are mostly animated images.
 *
 * Each page occupies the whole viewport and is centered. Tapping the middle opens the reader
 * settings; tapping the usual top/bottom page-turn zones recenters the current animation.
 */
@Composable
fun AnimatedPlaybackViewer(
    lazyListState: LazyListState,
    pageLoader: PageLoader,
    neighbors: Boolean,
    preloadCount: Int,
    speed: Float,
    allAnimated: Boolean,
    appbarVisible: Boolean,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Decoded animation pages are expensive (~2 full-size bitmaps each). Keep the configured
    // forward preload window, but bound it so the Java heap is not exhausted on large pages.
    val cacheAhead = preloadCount.coerceIn(1, MAX_ANIMATED_PRELOAD)
    var currentIndex by remember(pageLoader) { mutableIntStateOf(pageLoader.startPage) }
    var ready by remember(pageLoader) { mutableStateOf(false) }
    var playbackGeneration by remember(pageLoader) { mutableIntStateOf(0) }
    val advancing = remember(pageLoader) { mutableStateOf(false) }
    var advanceJob by remember(pageLoader) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val autoPageTurn by Settings.autoPageTurn.collectAsState()
    val autoPageTurnInterval by Settings.autoPageTurnInterval.collectAsState()
    // 禁止长距离惯性滑飞：单次上下滑动最多吸附到相邻上一张或下一张，确保整页居中切换。
    val snapLayoutInfoProvider = remember(lazyListState) {
        SinglePageSnapLayoutInfoProvider(SnapLayoutInfoProvider(lazyListState, SnapPosition.Start))
    }
    val snapFlingBehavior = rememberSnapFlingBehavior(snapLayoutInfoProvider = snapLayoutInfoProvider)

    // 仅订阅当前展示页的状态，避免在大画廊里创建上百/上千个顶层协程监听触发全树高频重组。
    val currentStatus = pageLoader.pages.getOrNull(currentIndex)?.statusObserved

    // 初次挂载时确保列表滚到当前页，避免从 Pager 切换进来时被第 0 页覆盖进度。
    LaunchedEffect(pageLoader, lazyListState) {
        if (lazyListState.firstVisibleItemIndex != currentIndex || lazyListState.firstVisibleItemScrollOffset != 0) {
            advancing.value = true
            try {
                lazyListState.scrollToItem(currentIndex)
            } finally {
                advancing.value = false
            }
        }
        snapshotFlow {
            Triple(
                lazyListState.layoutInfo.dominantVisibleItemIndex(currentIndex),
                lazyListState.isScrollInProgress,
                lazyListState.firstVisibleItemScrollOffset,
            )
        }.collect { (index, scrolling, offset) ->
            // Automatic advance already targets the next page; do not restart its animation.
            if (advancing.value) return@collect
            if (!scrolling) {
                if (offset != 0 || lazyListState.firstVisibleItemIndex != index) {
                    advancing.value = true
                    try {
                        lazyListState.scrollToItem(index)
                    } finally {
                        advancing.value = false
                    }
                }
                if (index != currentIndex) {
                    advanceJob?.cancel()
                    currentIndex = index
                    playbackGeneration++
                }
            }
        }
    }

    fun isCurrentAnimated(): Boolean = (currentStatus as? PageStatus.Ready)?.image?.isAnimated == true

    fun isReady(index: Int): Boolean = pageLoader.pages.getOrNull(index)?.status is PageStatus.Ready

    fun isTerminal(index: Int): Boolean = when (pageLoader.pages.getOrNull(index)?.status) {
        is PageStatus.Ready, is PageStatus.Error, is PageStatus.Blocked -> true
        else -> false
    }

    DisposableEffect(pageLoader) {
        onDispose {
            advanceJob?.cancel()
            pageLoader.extraPrefetchCount = 0
            pageLoader.setHotRange(null)
        }
    }

    // 仅在真正切页或手动重播时重置 playbackStarted，避免调整预加载数时清零导致切页卡死。
    LaunchedEffect(pageLoader, currentIndex, playbackGeneration) {
        AnimatedPlaybackSession.playbackStarted = false
    }

    LaunchedEffect(pageLoader, currentIndex, cacheAhead) {
        if (pageLoader.size <= 0) return@LaunchedEffect
        pageLoader.extraPrefetchCount = cacheAhead
        // Pin the page being shown and the page that will be shown next. Eviction between
        // decode and handoff was what produced the one-frame placeholder and loading blink.
        pageLoader.setHotRange(currentIndex..minOf(pageLoader.size - 1, currentIndex + 1))
        pageLoader.trimToWindow(currentIndex, cacheAhead)
        val end = minOf(pageLoader.size - 1, currentIndex + cacheAhead)
        pageLoader.requestRange(currentIndex, end)
        if (isTerminal(currentIndex)) {
            // 已就绪或已失败：不让全屏转圈遮挡画面或错误重试按钮。
            ready = true
            return@LaunchedEffect
        }
        ready = false
        var waited = 0L
        while (isActive && waited < PLAYBACK_READY_TIMEOUT_MILLIS) {
            if (isTerminal(currentIndex)) {
                ready = true
                return@LaunchedEffect
            }
            if (waited % 1_000L == 0L) {
                pageLoader.request(currentIndex)
            }
            delay(PLAYBACK_PRELOAD_POLL_MILLIS)
            waited += PLAYBACK_PRELOAD_POLL_MILLIS
        }
        ready = true
    }

    fun advance(fromIndex: Int) {
        if (fromIndex != currentIndex) return
        val immediateNext = fromIndex + 1
        if (immediateNext >= pageLoader.size) return
        advanceJob?.cancel()
        advanceJob = scope.launch {
            var target: Int? = null
            var waited = 0L
            while (isActive && waited < PLAYBACK_NEXT_TIMEOUT_MILLIS) {
                if (fromIndex != currentIndex) return@launch
                // 优先顺序等待紧邻的下一页，避免并发解码下后页先好导致跳过未解完的下一页。
                val nextStatus = pageLoader.pages.getOrNull(immediateNext)?.status
                if (nextStatus is PageStatus.Ready) {
                    target = immediateNext
                    break
                }
                if (nextStatus is PageStatus.Error || nextStatus is PageStatus.Blocked) {
                    target = (immediateNext + 1 until pageLoader.size).firstOrNull(::isReady) ?: immediateNext
                    break
                }
                pageLoader.requestRange(
                    immediateNext,
                    minOf(pageLoader.size - 1, fromIndex + cacheAhead),
                )
                delay(PLAYBACK_PRELOAD_POLL_MILLIS)
                waited += PLAYBACK_PRELOAD_POLL_MILLIS
            }
            if (fromIndex != currentIndex) return@launch
            val chosen = target ?: (immediateNext until pageLoader.size).firstOrNull(::isReady)
            if (chosen != null) {
                // Combined with the pinned next image this makes the handoff a single instant
                // page swap instead of a scroll animation through half-drawn pages.
                advancing.value = true
                try {
                    lazyListState.scrollToItem(chosen)
                    currentIndex = chosen
                } finally {
                    advancing.value = false
                }
            }
        }
    }

    // 静态页沿用自动翻页设置，并受菜单显隐和前台生命周期约束。
    if (ready && !appbarVisible && autoPageTurn && !isCurrentAnimated() && currentStatus is PageStatus.Ready) {
        LifecycleResumeEffect(currentIndex, autoPageTurnInterval, pageLoader.size) {
            val job = scope.launch {
                delay(autoPageTurnInterval.coerceAtLeast(1) * 1000L)
                if (currentIndex < pageLoader.size - 1 && !isCurrentAnimated()) {
                    advance(currentIndex)
                }
            }
            onPauseOrDispose { job.cancel() }
        }
    }

    Box(
        modifier = modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(
                onDoubleTap = {
                    scope.launch {
                        advancing.value = true
                        try {
                            lazyListState.scrollToItem(currentIndex)
                        } finally {
                            advancing.value = false
                        }
                    }
                },
                onTap = { offset ->
                    val edge = size.height * 0.30f
                    if (offset.y < edge || offset.y > size.height - edge) {
                        scope.launch {
                            advancing.value = true
                            try {
                                lazyListState.scrollToItem(currentIndex)
                            } finally {
                                advancing.value = false
                            }
                        }
                    } else {
                        onMenu()
                    }
                },
                onLongPress = { onMenu() },
            )
        },
    ) {
        LazyColumn(
            state = lazyListState,
            userScrollEnabled = neighbors,
            flingBehavior = snapFlingBehavior,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(pageLoader.pages, key = { it.index }) { page ->
                Box(
                    modifier = Modifier.fillMaxWidth().fillParentMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    PagerItem(
                        page = page,
                        pageLoader = pageLoader,
                        contentScale = ContentScale.Fit,
                        playbackActive = page.index == currentIndex,
                        playbackRunning = ready,
                        playbackStay = false,
                        playbackKey = playbackGeneration,
                        playbackSpeed = speed,
                        onAnimationStart = {
                            if (page.index == currentIndex) AnimatedPlaybackSession.playbackStarted = true
                        },
                        onAnimationEnd = {
                            if (page.index == currentIndex && AnimatedPlaybackSession.playbackStarted) {
                                advance(page.index)
                            }
                        },
                    )
                }
            }
        }
        if (!ready) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

private const val PLAYBACK_READY_TIMEOUT_MILLIS = 45_000L
private const val PLAYBACK_NEXT_TIMEOUT_MILLIS = 8_000L
private const val PLAYBACK_PRELOAD_POLL_MILLIS = 200L

/**
 * 单页吸附提供器：将惯性预滑距离归零，使上下滑动只切换相邻上一页或下一页。
 */
internal class SinglePageSnapLayoutInfoProvider(
    private val delegate: SnapLayoutInfoProvider,
) : SnapLayoutInfoProvider {
    override fun calculateApproachOffset(velocity: Float, decayOffset: Float): Float = 0f

    override fun calculateSnapOffset(velocity: Float): Float = delegate.calculateSnapOffset(velocity)
}

/**
 * 从当前可见项的像素占比中挑选显示面积最大的页索引，列表为空时回退至 [fallbackIndex]。
 */
internal fun resolveDominantVisiblePageIndex(
    visibleItems: List<Pair<Int, Int>>,
    fallbackIndex: Int,
): Int = visibleItems.maxByOrNull { (_, visiblePx) -> visiblePx }?.first ?: fallbackIndex

/**
 * 计算当前列表视口内占据像素高度最多的一页索引。
 */
internal fun LazyListLayoutInfo.dominantVisibleItemIndex(fallback: Int): Int {
    if (visibleItemsInfo.isEmpty()) return fallback
    val start = viewportStartOffset
    val end = viewportEndOffset
    val pairs = visibleItemsInfo.map { item ->
        val itemStart = item.offset
        val itemEnd = item.offset + item.size
        val visiblePx = (minOf(itemEnd, end) - maxOf(itemStart, start)).coerceAtLeast(0)
        item.index to visiblePx
    }
    return resolveDominantVisiblePageIndex(pairs, fallback)
}
