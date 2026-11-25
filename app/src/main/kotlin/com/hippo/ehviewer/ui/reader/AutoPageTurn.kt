package com.hippo.ehviewer.ui.reader

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.gallery.PageLoader
import com.hippo.ehviewer.gallery.PageStatus
import com.hippo.ehviewer.gallery.status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Advances the reader while the auto page turn preference is enabled.
 *
 * The timer is cancelled while the reader menu is visible and while the activity is not resumed,
 * so it cannot turn pages behind a dialog or in the background. A page is only turned once its
 * image has finished loading, which prevents auto reading from skipping over slow downloads.
 */
@Composable
fun AutoPageTurnEffect(
    isWebtoon: Boolean,
    appbarVisible: Boolean,
    pagerState: PagerState,
    lazyListState: LazyListState,
    pageLoader: PageLoader,
) {
    val enabled by Settings.autoPageTurn.collectAsState()
    val intervalSeconds by Settings.autoPageTurnInterval.collectAsState()
    if (!enabled || appbarVisible) return

    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(isWebtoon, intervalSeconds, pageLoader.size) {
        val intervalMillis = intervalSeconds.coerceAtLeast(1) * 1000L
        val job = scope.launch {
            if (isWebtoon) {
                autoScrollWebtoon(lazyListState, pageLoader, intervalMillis)
            } else {
                autoAdvancePager(pagerState, pageLoader, intervalMillis)
            }
        }
        onPauseOrDispose { job.cancel() }
    }
}

private suspend fun CoroutineScope.autoAdvancePager(
    pagerState: PagerState,
    pageLoader: PageLoader,
    intervalMillis: Long,
) {
    val lastPage = pageLoader.size - 1
    if (lastPage < 0) return

    while (isActive) {
        val pageIndex = pagerState.currentPage
        if (pageIndex >= lastPage) {
            Settings.autoPageTurn.value = false
            return
        }

        delay(intervalMillis)
        if (pagerState.currentPage != pageIndex) continue

        if (!awaitPageReady(pagerState, pageLoader, pageIndex)) {
            // The page was changed by the user while waiting, so start a fresh timer.
            if (pagerState.currentPage != pageIndex) continue
            Settings.autoPageTurn.value = false
            return
        }

        if (pagerState.currentPage != pageIndex) continue
        pagerState.moveToNext()
    }
}

private suspend fun CoroutineScope.awaitPageReady(
    pagerState: PagerState,
    pageLoader: PageLoader,
    pageIndex: Int,
): Boolean {
    var waitedMillis = 0L
    while (isActive && waitedMillis < PAGE_READY_TIMEOUT_MILLIS) {
        when (pageLoader.pages[pageIndex].status) {
            is PageStatus.Ready -> return true
            is PageStatus.Error, is PageStatus.Blocked -> return false
            else -> {
                if (pagerState.currentPage != pageIndex) return false
                delay(PAGE_READY_POLL_MILLIS)
                waitedMillis += PAGE_READY_POLL_MILLIS
            }
        }
    }
    return false
}

private suspend fun CoroutineScope.autoScrollWebtoon(
    lazyListState: LazyListState,
    pageLoader: PageLoader,
    intervalMillis: Long,
) {
    // Wait for the first layout pass before checking whether the list can scroll.
    var waitedMillis = 0L
    while (
        isActive &&
        lazyListState.layoutInfo.totalItemsCount == 0 &&
        waitedMillis < PAGE_READY_TIMEOUT_MILLIS
    ) {
        delay(PAGE_READY_POLL_MILLIS)
        waitedMillis += PAGE_READY_POLL_MILLIS
    }

    while (isActive) {
        if (!lazyListState.canScrollForward) {
            Settings.autoPageTurn.value = false
            return
        }

        val pageIndex = lazyListState.currentWebtoonPageIndex().coerceIn(0, (pageLoader.size - 1).coerceAtLeast(0))
        if (pageLoader.size > 0 && !awaitWebtoonPageReady(lazyListState, pageLoader, pageIndex)) {
            if (lazyListState.currentWebtoonPageIndex() != pageIndex) continue
            Settings.autoPageTurn.value = false
            return
        }

        delay(intervalMillis)
        if (!lazyListState.canScrollForward) continue
        lazyListState.scrollWebtoonPage(
            forward = true,
            pageLoader = pageLoader,
            sidePaddingPercent = Settings.webtoonSidePadding.value,
        )
    }
}

private suspend fun CoroutineScope.awaitWebtoonPageReady(
    lazyListState: LazyListState,
    pageLoader: PageLoader,
    pageIndex: Int,
): Boolean {
    var waitedMillis = 0L
    while (isActive && waitedMillis < PAGE_READY_TIMEOUT_MILLIS) {
        when (pageLoader.pages[pageIndex].status) {
            is PageStatus.Ready -> return true
            is PageStatus.Error, is PageStatus.Blocked -> return false
            else -> {
                if (lazyListState.currentWebtoonPageIndex() != pageIndex) return false
                delay(PAGE_READY_POLL_MILLIS)
                waitedMillis += PAGE_READY_POLL_MILLIS
            }
        }
    }
    return false
}

private const val PAGE_READY_TIMEOUT_MILLIS = 15_000L
private const val PAGE_READY_POLL_MILLIS = 250L
