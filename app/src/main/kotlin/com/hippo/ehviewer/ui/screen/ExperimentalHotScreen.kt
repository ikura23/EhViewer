package com.hippo.ehviewer.ui.screen

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.launch
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.client.data.ListUrlBuilder.Companion.MODE_SUBSCRIPTION
import com.hippo.ehviewer.client.data.ListUrlBuilder.Companion.MODE_TOPLIST
import com.hippo.ehviewer.client.data.ListUrlBuilder.Companion.MODE_WHATS_HOT
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.FavouritesScreenDestination
import com.hippo.ehviewer.ui.doGalleryInfoAction
import com.hippo.ehviewer.ui.main.AvatarIcon
import com.hippo.ehviewer.ui.main.GalleryInfoGridItem
import com.hippo.ehviewer.ui.main.GalleryInfoListItem
import com.hippo.ehviewer.ui.main.GalleryList
import com.hippo.ehviewer.util.FavouriteStatusRouter
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import moe.tarsin.navigate

/**
 * 实验性新版「热门」聚合页：整合 热门 / 排行（内置周期筛选）/ 订阅 三个高频发现入口。
 */
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.ExperimentalHotScreen(
    navigator: DestinationsNavigator,
) = Screen(navigator) {
    var hotTab by rememberSaveable { mutableIntStateOf(0) }
    var listMode by Settings.listMode.asMutableState()
    val showPages by Settings.showGalleryPages.collectAsState()
    val showProgress by Settings.showReadingProgress.collectAsState()
    val height by collectListThumbSizeAsState()

    val entries = stringArrayResource(id = com.hippo.ehviewer.R.array.toplist_entries)
    val values = stringArrayResource(id = com.hippo.ehviewer.R.array.toplist_values)
    val toplists = remember { entries zip values }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        // 顶部简洁操作栏：标题 + 视图切换 + 收藏红心 + 配额头像
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Whatshot,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = stringResource(R.string.nav_tab_hot),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            IconButton(onClick = { listMode = if (listMode == 0) 1 else 0 }) {
                Icon(
                    imageVector = if (listMode == 0) Icons.Default.GridView else Icons.AutoMirrored.Default.ViewList,
                    contentDescription = null,
                )
            }
            IconButton(onClick = { navigate(FavouritesScreenDestination) }) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = stringResource(R.string.favourite),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            AvatarIcon()
        }

        // 三段切换：热门 / 排行 / 订阅
        val segs = listOf(
            Triple(0, R.string.whats_hot, Icons.Default.Whatshot),
            Triple(1, R.string.toplist, Icons.AutoMirrored.Default.FormatListBulleted),
            Triple(2, R.string.subscription, Icons.Default.FolderSpecial),
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            segs.forEachIndexed { idx, (tabId, labelRes, icon) ->
                SegmentedButton(
                    selected = hotTab == tabId,
                    onClick = { hotTab = tabId },
                    shape = SegmentedButtonDefaults.itemShape(index = idx, count = segs.size),
                    icon = { Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text(text = stringResource(labelRes), maxLines = 1) },
                )
            }
        }

        // 每个分支独立 ViewModel key，防止 Paging 状态互相污染
        key(hotTab) {
            val viewModel: GalleryListViewModel = viewModel(key = "exp_hot_$hotTab") {
                val initialLub = when (hotTab) {
                    0 -> ListUrlBuilder(MODE_WHATS_HOT)
                    1 -> ListUrlBuilder(MODE_TOPLIST, keyword = toplists.firstOrNull()?.second ?: "15")
                    else -> ListUrlBuilder(MODE_SUBSCRIPTION)
                }
                GalleryListViewModel(initialLub, createSavedStateHandle())
            }
            var urlBuilder by viewModel.urlBuilder
            val data = viewModel.data.collectAsLazyPagingItems()
            FavouriteStatusRouter.Observe(data)
            val listState = rememberLazyGridState()
            val gridState = rememberLazyStaggeredGridState()

            if (hotTab == 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(toplists) { (name, keyword) ->
                        FilterChip(
                            selected = urlBuilder.keyword == keyword,
                            onClick = {
                                Settings.recentToplist = keyword
                                urlBuilder = ListUrlBuilder(MODE_TOPLIST, keyword = keyword)
                                data.refresh()
                            },
                            label = { Text(text = name) },
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                GalleryList(
                    data = data,
                    contentPadding = PaddingValues(bottom = 12.dp),
                    listMode = listMode,
                    detailListState = listState,
                    detailItemContent = { info ->
                        GalleryInfoListItem(
                            onClick = { navigate(info.asDst()) },
                            onLongClick = { launch { doGalleryInfoAction(info) } },
                            info = info,
                            showPages = showPages,
                            showProgress = showProgress,
                            modifier = Modifier.height(height),
                        )
                    },
                    thumbListState = gridState,
                    thumbItemContent = { info ->
                        GalleryInfoGridItem(
                            onClick = { navigate(info.asDst()) },
                            onLongClick = { launch { doGalleryInfoAction(info) } },
                            info = info,
                            showPages = showPages,
                            showProgress = showProgress,
                        )
                    },
                    searchBarOffsetY = { 0 },
                    onRefresh = {
                        urlBuilder.setRange(0)
                        data.refresh()
                    },
                    onLoading = {},
                )
            }
        }
    }
}
