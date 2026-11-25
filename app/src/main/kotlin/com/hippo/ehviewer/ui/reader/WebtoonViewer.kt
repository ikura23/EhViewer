package com.hippo.ehviewer.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ehviewer.core.ui.util.thenIf
import com.hippo.ehviewer.READER_THEME_SMART
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.coil.applyReaderColorFilters
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.PageLoader
import com.hippo.ehviewer.gallery.PageStatus
import com.hippo.ehviewer.gallery.statusObserved
import eu.kanade.tachiyomi.ui.reader.viewer.NavigationRegions
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import eu.kanade.tachiyomi.ui.reader.viewer.getAction
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.OverzoomEffect
import me.saket.telephoto.zoomable.ZoomLimit
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

@Composable
fun WebtoonViewer(
    lazyListState: LazyListState,
    withGaps: Boolean,
    pageLoader: PageLoader,
    navigator: () -> NavigationRegions,
    onSelectPage: (Page) -> Unit,
    onMenuRegionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val items = pageLoader.pages
    val zoomableState = rememberZoomableState(zoomSpec = WebtoonZoomSpec)
    val density = LocalDensity.current
    val paddingPercent by Settings.webtoonSidePadding.collectAsState()
    val readerTheme by Settings.readerTheme.collectAsState()
    val grayScale by Settings.grayScale.collectAsState()
    val invertedColors by Settings.invertedColors.collectAsState()
    val smartBackgroundEnabled = readerTheme == READER_THEME_SMART
    val sidePadding by remember(density) {
        snapshotFlow {
            with(density) {
                (lazyListState.layoutInfo.viewportSize.width * paddingPercent / 100f).toDp()
            }
        }
    }.collectAsState(0.dp)
    LazyColumn(
        modifier = modifier.zoomable(
            state = zoomableState,
            onClick = { offset ->
                scope.launch {
                    with(lazyListState) {
                        val (w, h) = layoutInfo.viewportSize
                        val (x, y) = offset
                        when (navigator().getAction(Offset(x / w, y / h))) {
                            NavigationRegion.MENU -> onMenuRegionClick()
                            NavigationRegion.NEXT, NavigationRegion.RIGHT -> scrollWebtoonPage(
                                forward = true,
                                pageLoader = pageLoader,
                                sidePaddingPercent = paddingPercent,
                            )
                            NavigationRegion.PREV, NavigationRegion.LEFT -> scrollWebtoonPage(
                                forward = false,
                                pageLoader = pageLoader,
                                sidePaddingPercent = paddingPercent,
                            )
                        }
                    }
                }
            },
            onLongClick = { ofs ->
                val info = lazyListState.layoutInfo.visibleItemsInfo.find { info ->
                    info.offset <= ofs.y && info.offset + info.size > ofs.y
                }
                if (info != null) {
                    onSelectPage(items[info.index])
                }
            },
            onDoubleClick = DoubleTapZoom,
        ),
        state = lazyListState,
        contentPadding = PaddingValues(horizontal = sidePadding),
        verticalArrangement = Arrangement.spacedBy(if (withGaps) 15.dp else 0.dp),
        cacheWindow = LazyLayoutCacheWindow(SCROLL_FRACTION, SCROLL_FRACTION),
    ) {
        items(items, key = { it.index }) { page ->
            val pageBackgroundColor = if (smartBackgroundEnabled) {
                val status = page.statusObserved
                if (status is PageStatus.Ready) {
                    status.image.backgroundColor?.let { raw ->
                        Color(applyReaderColorFilters(raw, grayScale, invertedColors))
                    }
                } else {
                    null
                }
            } else {
                null
            }
            PagerItem(
                page = page,
                pageLoader = pageLoader,
                contentScale = ContentScale.FillWidth,
                contentModifier = Modifier.thenIf(pageBackgroundColor != null) {
                    background(pageBackgroundColor!!)
                },
            )
        }
    }
}

private val WebtoonZoomSpec = ZoomSpec(
    maximum = ZoomLimit(factor = 3f),
    minimum = ZoomLimit(factor = 1f, overzoomEffect = OverzoomEffect.Disabled),
)
