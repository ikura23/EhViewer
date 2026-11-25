package com.hippo.ehviewer.ui.reader

import androidx.compose.foundation.lazy.LazyListState
import com.hippo.ehviewer.PAGE_EDGE_ALIGN_BOTTOM
import com.hippo.ehviewer.PAGE_EDGE_ALIGN_CENTER
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.PageLoader
import com.hippo.ehviewer.gallery.PageStatus
import com.hippo.ehviewer.gallery.status
import kotlin.math.roundToInt

/**
 * Scrolls a continuous vertical reader to the top edge of the next page.
 *
 * In continuous vertical mode there are no discrete pager pages, so the next page is derived
 * from the page that currently occupies most of the viewport. At the last page this falls back
 * to the normal fraction-of-viewport scroll so auto reading can still reach the end of a long page.
 */
suspend fun LazyListState.scrollToNextPageEdge(pageLoader: PageLoader, sidePaddingPx: Int) {
    if (pageLoader.size <= 0) return
    val currentIndex = currentWebtoonPageIndex()
    val currentItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == currentIndex }
    // 长图底部尚未进入视口时，优先继续向下滚动阅读本页剩余内容，避免跳过下半截。
    if (currentItem != null && currentItem.offset + currentItem.size > layoutInfo.viewportEndOffset + 2) {
        scrollDown()
        return
    }
    val targetIndex = (currentIndex + 1).coerceAtMost(pageLoader.size - 1)
    if (targetIndex == currentIndex) {
        scrollDown()
        return
    }
    scrollToPageEdge(
        targetIndex,
        alignment = if (Settings.pageEdgeAlignmentCenter.value) {
            PAGE_EDGE_ALIGN_CENTER
        } else {
            Settings.pageEdgeAlignmentNext.value
        },
        pageLoader,
        sidePaddingPx,
    )
}

/**
 * Performs one page-turn action in a continuous vertical reader.
 *
 * When page edge alignment is disabled this preserves the original fraction-of-viewport scroll.
 */
suspend fun LazyListState.scrollWebtoonPage(
    forward: Boolean,
    pageLoader: PageLoader,
    sidePaddingPercent: Int,
) {
    val sidePaddingPx = (layoutInfo.viewportSize.width * sidePaddingPercent / 100f).roundToInt()
    if (Settings.pageEdgeAlignment.value) {
        if (forward) {
            scrollToNextPageEdge(pageLoader, sidePaddingPx)
        } else {
            scrollToPreviousPageEdge(pageLoader, sidePaddingPx)
        }
    } else {
        if (forward) scrollDown() else scrollUp()
    }
}

/**
 * Scrolls a continuous vertical reader to the bottom edge of the previous page.
 */
suspend fun LazyListState.scrollToPreviousPageEdge(pageLoader: PageLoader, sidePaddingPx: Int) {
    if (pageLoader.size <= 0) return
    val currentIndex = currentWebtoonPageIndex()
    val currentItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == currentIndex }
    // 长图顶部尚未进入视口时，先向上滚动看完本页上半截再退回上一页。
    if (currentItem != null && currentItem.offset < layoutInfo.viewportStartOffset - 2) {
        scrollUp()
        return
    }
    val targetIndex = currentIndex - 1
    if (targetIndex < 0) {
        if (Settings.pageTransitions.value) animateScrollToItem(0) else scrollToItem(0)
        return
    }
    scrollToPageEdge(
        targetIndex,
        alignment = if (Settings.pageEdgeAlignmentCenter.value) {
            PAGE_EDGE_ALIGN_CENTER
        } else {
            Settings.pageEdgeAlignmentPrev.value
        },
        pageLoader,
        sidePaddingPx,
    )
}

private suspend fun LazyListState.scrollToPageEdge(
    index: Int,
    alignment: Int,
    pageLoader: PageLoader,
    sidePaddingPx: Int,
) {
    val viewportHeight = layoutInfo.viewportSize.height
    val itemHeight = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size
        ?: estimatePageHeight(pageLoader.pages[index], layoutInfo.viewportSize.width - 2 * sidePaddingPx)
    val scrollOffset = when {
        alignment == PAGE_EDGE_ALIGN_BOTTOM && itemHeight != null -> itemHeight - viewportHeight
        alignment == PAGE_EDGE_ALIGN_CENTER && itemHeight != null -> (itemHeight - viewportHeight) / 2
        else -> 0
    }
    if (Settings.pageTransitions.value) {
        animateScrollToItem(index, scrollOffset)
    } else {
        scrollToItem(index, scrollOffset)
    }
}

internal fun LazyListState.currentWebtoonPageIndex(): Int {
    val visible = layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return firstVisibleItemIndex
    val viewStart = layoutInfo.viewportStartOffset
    val viewEnd = layoutInfo.viewportEndOffset
    val center = (viewStart + viewEnd) / 2
    // 优先取覆盖视口中心点的条目，其次取在视口内可见高度占比最大的条目，避免被下方短图抢占当前页。
    visible.firstOrNull { center in it.offset until (it.offset + it.size) }?.let { return it.index }
    return visible.maxByOrNull { item ->
        (minOf(item.offset + item.size, viewEnd) - maxOf(item.offset, viewStart)).coerceAtLeast(0)
    }?.index ?: firstVisibleItemIndex
}

private fun estimatePageHeight(page: Page, availableWidth: Int): Int? {
    if (availableWidth <= 0) return null
    val status = page.status
    if (status !is PageStatus.Ready) return null
    val size = status.image.intrinsicSize
    if (size.width <= 0) return null
    return (size.height.toFloat() * availableWidth / size.width).roundToInt()
}
