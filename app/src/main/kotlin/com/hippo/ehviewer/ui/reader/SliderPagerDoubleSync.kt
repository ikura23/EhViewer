package com.hippo.ehviewer.ui.reader

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.ehviewer.core.ui.component.IntSliderState
import com.hippo.ehviewer.gallery.PageLoader
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull

@Stable
class SliderPagerDoubleSync(
    private val lazyListState: LazyListState,
    private val pagerState: PagerState,
    private val pageLoader: PageLoader,
) {
    private var sliderFollowPager by mutableStateOf(true)
    private var committedPage by mutableIntStateOf(pageLoader.startPage + 1)
    val slider = IntSliderState(pageLoader.startPage + 1, 1..pageLoader.size)

    /** 仅更新滑块目标页码预览，松手前不触发实际翻页。 */
    fun sliderPreviewTo(index: Int) {
        sliderFollowPager = false
        slider.value = index
    }

    /** 提交当前滑块目标页码，触发实际翻页跳转。 */
    fun sliderCommit() {
        sliderFollowPager = false
        committedPage = slider.value
    }

    /** 立即同步滑块并跳转至目标页码（用于无延迟外部跳转）。 */
    fun sliderScrollTo(index: Int) {
        sliderFollowPager = false
        slider.value = index
        committedPage = index
    }

    fun reset() {
        sliderFollowPager = true
    }

    fun currentPageFlow(webtoon: Boolean) = if (webtoon) {
        snapshotFlow {
            with(lazyListState.layoutInfo) {
                visibleItemsInfo.lastOrNull {
                    it.offset <= maxOf(viewportStartOffset, viewportEndOffset - it.size)
                }?.index
            }
        }.filterNotNull()
    } else {
        snapshotFlow { pagerState.currentPage }
    }

    @Composable
    fun Sync(webtoon: Boolean, onPageSelected: () -> Unit) {
        val currentIndexFlow = remember(webtoon) {
            val initialIndex = slider.value - 1
            sliderFollowPager = if (webtoon) {
                lazyListState.firstVisibleItemIndex == initialIndex
            } else {
                pagerState.currentPage == initialIndex
            }
            currentPageFlow(webtoon)
        }
        if (sliderFollowPager) {
            LaunchedEffect(currentIndexFlow) {
                currentIndexFlow.drop(1).collect { index ->
                    slider.value = index + 1
                    committedPage = index + 1
                    pageLoader.startPage = index
                    onPageSelected()
                }
            }
        } else {
            LaunchedEffect(webtoon) {
                snapshotFlow { committedPage - 1 }.collectLatest { index ->
                    if (webtoon) {
                        lazyListState.scrollToItem(index)
                    } else {
                        pagerState.animateScrollToPage(index)
                    }
                    pageLoader.startPage = index
                    // 跳转完成后恢复列表/Pager 对滑块的反向同步，避免后续手势翻页进度条滞留。
                    sliderFollowPager = true
                }
            }
        }
    }
}

@Stable
@Composable
fun rememberSliderPagerDoubleSyncState(
    lazyListState: LazyListState,
    pagerState: PagerState,
    pageLoader: PageLoader,
): SliderPagerDoubleSync = remember {
    SliderPagerDoubleSync(lazyListState, pagerState, pageLoader)
}
