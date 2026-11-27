package com.hippo.ehviewer.ui.screen

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.ehviewer.core.database.model.QuickSearch
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.ImageSearchScreenDestination
import com.hippo.ehviewer.ui.experimental.ExperimentalSearchFilterState
import com.hippo.ehviewer.ui.experimental.RecommendTabMode
import com.hippo.ehviewer.ui.experimental.RecommendedTagItem
import com.hippo.ehviewer.ui.experimental.findLanguageFilterIndex
import com.hippo.ehviewer.ui.main.requestOf
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import moe.tarsin.navigate

private val experimentalCategoryEntries = listOf(
    EhUtils.DOUJINSHI to R.string.doujinshi,
    EhUtils.MANGA to R.string.manga,
    EhUtils.ARTIST_CG to R.string.artist_cg,
    EhUtils.GAME_CG to R.string.game_cg,
    EhUtils.WESTERN to R.string.western,
    EhUtils.NON_H to R.string.non_h,
    EhUtils.IMAGE_SET to R.string.image_set,
    EhUtils.COSPLAY to R.string.cosplay,
    EhUtils.ASIAN_PORN to R.string.asian_porn,
    EhUtils.MISC to R.string.misc,
)

/**
 * 实验性新版搜索页：输入检索 + 高频快捷筛选条 + 推荐标签画幅墙 + 高栏位高级筛选大面板。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.ExperimentalSearchScreen(
    navigator: DestinationsNavigator,
) = Screen(navigator) {
    var queryText by rememberSaveable { mutableStateOf("") }
    var searchSubTab by rememberSaveable { mutableIntStateOf(0) }
    var filterState by remember { mutableStateOf(ExperimentalSearchFilterState()) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showQuickSearchSheet by remember { mutableStateOf(false) }

    val quickSearches by produceState<List<QuickSearch>>(emptyList(), showQuickSearchSheet) {
        if (showQuickSearchSheet) {
            value = withIOContext { EhDB.getAllQuickSearch() }
        }
    }
    val nonHMode by Settings.nonHMode.collectAsState()
    val gallerySite by Settings.gallerySite.collectAsState()
    val currentTabMode = when (searchSubTab) {
        0 -> RecommendTabMode.TAGS
        1 -> RecommendTabMode.PARODIES
        else -> RecommendTabMode.ARTISTS
    }
    val searchViewModel: ExperimentalSearchViewModel = viewModel()
    val recommendState by searchViewModel.uiStateFlow(currentTabMode).collectAsState()

    LaunchedEffect(currentTabMode, nonHMode, gallerySite) {
        searchViewModel.ensureLoaded(currentTabMode, nonHMode, gallerySite)
    }

    val zhIdx = remember { findLanguageFilterIndex("chinese") }
    val jaIdx = remember { findLanguageFilterIndex("japanese") }
    val enIdx = remember { findLanguageFilterIndex("english") }

    fun executeSearch(
        keywordOverride: String? = null,
        pinnedGallery: BaseGalleryInfo? = null,
    ) {
        val q = keywordOverride ?: queryText
        val lub = filterState.buildListUrlBuilder(q)
        navigate(lub.asDst(pinnedGallery))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        // 顶部药丸输入栏
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .height(50.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (queryText.isEmpty()) {
                        Text(
                            text = stringResource(R.string.gallery_list_search_bar_hint_e_hentai),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = queryText,
                        onValueChange = { queryText = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { executeSearch() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (queryText.isNotEmpty()) {
                    IconButton(onClick = { queryText = "" }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = null)
                    }
                }
                IconButton(onClick = { navigate(ImageSearchScreenDestination) }) {
                    Icon(
                        imageVector = Icons.Default.ImageSearch,
                        contentDescription = stringResource(R.string.image_search),
                    )
                }
                IconButton(onClick = { showQuickSearchSheet = true }) {
                    Icon(
                        imageVector = Icons.Default.Bookmarks,
                        contentDescription = stringResource(R.string.quick_search),
                    )
                }
                val activeCount = filterState.activeCount()
                IconButton(onClick = { showFilterSheet = true }) {
                    Box {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = stringResource(R.string.search_filter_all),
                        )
                        if (activeCount > 0) {
                            Badge(
                                modifier = Modifier.align(Alignment.TopEnd).size(8.dp),
                            )
                        }
                    }
                }
            }
        }

        // 前置单行高频快捷筛选条
        val activeCount = filterState.activeCount()
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (nonHMode) {
                item {
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text(text = stringResource(R.string.search_rec_non_h_badge)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }
            item {
                FilterChip(
                    selected = activeCount > 0,
                    onClick = { showFilterSheet = true },
                    label = {
                        val txt = stringResource(R.string.search_filter_all)
                        Text(text = if (activeCount > 0) "$txt · $activeCount" else txt)
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }
            item {
                val ratingText = if (filterState.minRating > 0) {
                    "★${filterState.minRating}+"
                } else {
                    stringResource(R.string.search_sr)
                }
                FilterChip(
                    selected = filterState.minRating > 0,
                    onClick = { showFilterSheet = true },
                    label = { Text(text = ratingText) },
                )
            }
            item {
                val pageText = when {
                    filterState.fromPage > 0 && filterState.toPage > 0 ->
                        "${filterState.fromPage}–${filterState.toPage}P"
                    filterState.fromPage > 0 -> "${filterState.fromPage}P+"
                    filterState.toPage > 0 -> "≤${filterState.toPage}P"
                    else -> stringResource(R.string.key_pages)
                }
                FilterChip(
                    selected = filterState.fromPage > 0 || filterState.toPage > 0,
                    onClick = { showFilterSheet = true },
                    label = { Text(text = pageText) },
                )
            }
            item {
                FilterChip(
                    selected = filterState.onlyAnimated,
                    onClick = { filterState = filterState.copy(onlyAnimated = !filterState.onlyAnimated) },
                    label = { Text(text = stringResource(R.string.search_filter_only_animated)) },
                )
            }
            item {
                FilterChip(
                    selected = filterState.onlyTorrents,
                    onClick = { filterState = filterState.copy(onlyTorrents = !filterState.onlyTorrents) },
                    label = { Text(text = stringResource(R.string.search_sto)) },
                )
            }
            item {
                val langLabel = when (filterState.languageIndex) {
                    zhIdx -> "中文"
                    jaIdx -> "日本語"
                    enIdx -> "English"
                    -1 -> stringResource(R.string.key_language)
                    else -> stringResource(R.string.key_language)
                }
                FilterChip(
                    selected = filterState.languageIndex >= 0,
                    onClick = {
                        val next = when (filterState.languageIndex) {
                            -1 -> if (zhIdx >= 0) zhIdx else 0
                            zhIdx -> if (jaIdx >= 0) jaIdx else enIdx
                            jaIdx -> if (enIdx >= 0) enIdx else -1
                            else -> -1
                        }
                        filterState = filterState.copy(languageIndex = next)
                    },
                    label = { Text(text = langLabel) },
                )
            }
        }

        // 等分满宽 3 段切换，防止窄屏或默认边距下文字被截断
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            val subTabs = listOf(
                R.string.search_tab_tags,
                R.string.search_tab_parody,
                R.string.search_tab_artists,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                subTabs.forEachIndexed { idx, resId ->
                    SegmentedButton(
                        selected = searchSubTab == idx,
                        onClick = { searchSubTab = idx },
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = subTabs.size),
                        icon = {},
                        label = {
                            Text(
                                text = stringResource(resId),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }

        // 内容区：支持下拉刷新与下滑无限加载的高星推荐标签 / 热门原作 / 画师社团
        val refreshState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = recommendState.isRefreshing,
            onRefresh = { searchViewModel.refresh(currentTabMode, nonHMode, gallerySite) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = refreshState,
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    modifier = Modifier.align(Alignment.TopCenter),
                    isRefreshing = recommendState.isRefreshing,
                    state = refreshState,
                )
            },
        ) {
            when {
                recommendState.isInitialLoading && recommendState.items.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.search_rec_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                recommendState.errorMessage != null && recommendState.items.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.search_rec_error),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { searchViewModel.refresh(currentTabMode, nonHMode, gallerySite) },
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = stringResource(R.string.search_rec_retry))
                        }
                    }
                }
                recommendState.items.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.search_rec_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { searchViewModel.refresh(currentTabMode, nonHMode, gallerySite) },
                        ) {
                            Text(text = stringResource(R.string.search_rec_retry))
                        }
                    }
                }
                searchSubTab == 0 || searchSubTab == 1 -> {
                    val gridState = rememberLazyGridState()
                    LaunchedEffect(
                        gridState,
                        currentTabMode,
                        recommendState.items.size,
                        recommendState.hasMore,
                        recommendState.isLoadingMore,
                        recommendState.isRefreshing,
                    ) {
                        snapshotFlow {
                            val total = gridState.layoutInfo.totalItemsCount
                            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                            last to total
                        }
                            .distinctUntilChanged()
                            .collect { (last, total) ->
                                if (total > 0 && last >= total - 6 && recommendState.hasMore && !recommendState.isLoadingMore && !recommendState.isRefreshing) {
                                    searchViewModel.loadMore(currentTabMode, nonHMode, gallerySite)
                                }
                            }
                    }
                    val heroItem = if (searchSubTab == 0) recommendState.items.firstOrNull() else null
                    val gridItems = if (searchSubTab == 0 && heroItem != null) {
                        recommendState.items.drop(1)
                    } else {
                        recommendState.items
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 16.dp),
                    ) {
                        if (heroItem != null) {
                            item(key = "hero_${heroItem.tagKey}", span = { GridItemSpan(3) }) {
                                HeroRecommendedTagCard(
                                    item = heroItem,
                                    onClick = { executeSearch(heroItem.query, pinnedGallery = heroItem.coverGallery) },
                                )
                            }
                        }
                        items(gridItems, key = { it.tagKey }) { item ->
                            SquareRecommendedTagCard(
                                item = item,
                                onClick = { executeSearch(item.query, pinnedGallery = item.coverGallery) },
                            )
                        }
                        item(key = "footer_$currentTabMode", span = { GridItemSpan(3) }) {
                            RecommendListFooter(
                                itemCount = recommendState.items.size,
                                isLoadingMore = recommendState.isLoadingMore,
                                hasMore = recommendState.hasMore,
                                onLoadMore = { searchViewModel.loadMore(currentTabMode, nonHMode, gallerySite) },
                            )
                        }
                    }
                }
                else -> {
                    val listState = rememberLazyListState()
                    LaunchedEffect(
                        listState,
                        currentTabMode,
                        recommendState.items.size,
                        recommendState.hasMore,
                        recommendState.isLoadingMore,
                        recommendState.isRefreshing,
                    ) {
                        snapshotFlow {
                            val total = listState.layoutInfo.totalItemsCount
                            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                            last to total
                        }
                            .distinctUntilChanged()
                            .collect { (last, total) ->
                                if (total > 0 && last >= total - 4 && recommendState.hasMore && !recommendState.isLoadingMore && !recommendState.isRefreshing) {
                                    searchViewModel.loadMore(currentTabMode, nonHMode, gallerySite)
                                }
                            }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(recommendState.items, key = { it.tagKey }) { artist ->
                            RecommendedArtistCard(
                                item = artist,
                                onClick = { executeSearch(artist.query, pinnedGallery = artist.coverGallery) },
                            )
                        }
                        item(key = "footer_artists") {
                            RecommendListFooter(
                                itemCount = recommendState.items.size,
                                isLoadingMore = recommendState.isLoadingMore,
                                hasMore = recommendState.hasMore,
                                onLoadMore = { searchViewModel.loadMore(currentTabMode, nonHMode, gallerySite) },
                            )
                        }
                    }
                }
            }
        }
    }

    // 高栏位高级筛选大面板
    if (showFilterSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            ) {
                // 吸顶标题 + 重置全部
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.search_filter_all),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { filterState = ExperimentalSearchFilterState() },
                    ) {
                        Text(text = stringResource(R.string.search_filter_reset_all))
                    }
                }
                HorizontalDivider()

                // 可滚动多分组筛选表单
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // 1. 分类多选（中文）
                    Text(
                        text = stringResource(R.string.key_category),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        experimentalCategoryEntries.forEach { (bit, titleRes) ->
                            val selected = filterState.isCategoryChecked(bit)
                            FilterChip(
                                selected = selected,
                                onClick = { filterState = filterState.toggleCategory(bit) },
                                label = { Text(text = stringResource(titleRes)) },
                            )
                        }
                    }

                    // 2. 最低评分（星星数）
                    Text(
                        text = stringResource(R.string.search_sr),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 2, 3, 4, 5).forEach { r ->
                            FilterChip(
                                selected = filterState.minRating == r,
                                onClick = { filterState = filterState.copy(minRating = r) },
                                label = {
                                    Text(text = if (r == 0) stringResource(R.string.any) else "★ $r+")
                                },
                            )
                        }
                    }

                    // 3. 页数范围 + 快捷预设
                    Text(
                        text = stringResource(R.string.key_pages),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = filterState.fromPage.takeIf { it > 0 }?.toString().orEmpty(),
                            onValueChange = {
                                val v = it.filter(Char::isDigit).toIntOrNull() ?: 0
                                filterState = filterState.copy(fromPage = v.coerceIn(0, 5000))
                            },
                            label = { Text(text = "Min") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        Text(text = stringResource(R.string.search_sp_to))
                        OutlinedTextField(
                            value = filterState.toPage.takeIf { it > 0 }?.toString().orEmpty(),
                            onValueChange = {
                                val v = it.filter(Char::isDigit).toIntOrNull() ?: 0
                                filterState = filterState.copy(toPage = v.coerceIn(0, 5000))
                            },
                            label = { Text(text = "Max") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val presets = listOf(
                            Triple(0, 0, R.string.any),
                            Triple(0, 20, R.string.search_filter_pages_preset_short),
                            Triple(20, 60, R.string.search_filter_pages_preset_mid),
                            Triple(60, 0, R.string.search_filter_pages_preset_long),
                            Triple(120, 0, R.string.search_filter_pages_preset_tank),
                        )
                        presets.forEach { (fp, tp, resId) ->
                            FilterChip(
                                selected = filterState.fromPage == fp && filterState.toPage == tp,
                                onClick = { filterState = filterState.copy(fromPage = fp, toPage = tp) },
                                label = { Text(text = stringResource(resId)) },
                            )
                        }
                    }

                    // 4. 画廊语言
                    Text(
                        text = stringResource(R.string.key_language),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            -1 to stringResource(R.string.any),
                            zhIdx to "中文",
                            jaIdx to "日本語",
                            enIdx to "English",
                        ).filter { it.first == -1 || it.first >= 0 }.forEach { (idx, name) ->
                            FilterChip(
                                selected = filterState.languageIndex == idx,
                                onClick = { filterState = filterState.copy(languageIndex = idx) },
                                label = { Text(text = name) },
                            )
                        }
                    }

                    // 5. 附加条件与禁用默认排除项
                    Text(
                        text = stringResource(R.string.search_filter_disable_excludes),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    FilterSwitchRow(
                        label = stringResource(R.string.search_filter_only_animated),
                        checked = filterState.onlyAnimated,
                        onCheckedChange = { filterState = filterState.copy(onlyAnimated = it) },
                    )
                    FilterSwitchRow(
                        label = stringResource(R.string.search_sto),
                        checked = filterState.onlyTorrents,
                        onCheckedChange = { filterState = filterState.copy(onlyTorrents = it) },
                    )
                    FilterSwitchRow(
                        label = stringResource(R.string.search_sh),
                        checked = filterState.showExpunged,
                        onCheckedChange = { filterState = filterState.copy(showExpunged = it) },
                    )
                    FilterSwitchRow(
                        label = stringResource(R.string.search_sf) + stringResource(R.string.search_sfl),
                        checked = filterState.disableLangFilter,
                        onCheckedChange = { filterState = filterState.copy(disableLangFilter = it) },
                    )
                    FilterSwitchRow(
                        label = stringResource(R.string.search_sf) + stringResource(R.string.search_sfu),
                        checked = filterState.disableUploaderFilter,
                        onCheckedChange = { filterState = filterState.copy(disableUploaderFilter = it) },
                    )
                    FilterSwitchRow(
                        label = stringResource(R.string.search_sf) + stringResource(R.string.search_sft),
                        checked = filterState.disableTagFilter,
                        onCheckedChange = { filterState = filterState.copy(disableTagFilter = it) },
                    )
                }

                HorizontalDivider()
                // 吸底确认取消按钮
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = { showFilterSheet = false },
                        modifier = Modifier.weight(0.8f),
                    ) {
                        Text(text = stringResource(android.R.string.cancel))
                    }
                    Button(
                        onClick = {
                            showFilterSheet = false
                            executeSearch()
                        },
                        modifier = Modifier.weight(1.2f),
                    ) {
                        Text(text = stringResource(R.string.search_filter_apply))
                    }
                }
            }
        }
    }

    // 快速搜索书签弹层
    if (showQuickSearchSheet) {
        ModalBottomSheet(onDismissRequest = { showQuickSearchSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    text = stringResource(R.string.quick_search),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                if (quickSearches.isEmpty()) {
                    Text(
                        text = stringResource(R.string.quick_search_tip),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                } else {
                    quickSearches.forEach { qs ->
                        ListItem(
                            headlineContent = { Text(text = qs.name) },
                            supportingContent = qs.keyword?.let { { Text(text = it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                            modifier = Modifier.clickable {
                                showQuickSearchSheet = false
                                navigate(ListUrlBuilder(qs).asDst())
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroRecommendedTagCard(
    item: RecommendedTagItem,
    onClick: () -> Unit,
) {
    val starBadge = remember(item.coverGallery.rating) {
        String.format(Locale.US, "★ %.1f", item.coverGallery.rating.coerceIn(0f, 5f))
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .background(
                Brush.verticalGradient(
                    listOf(Color(item.gradientStartArgb), Color(item.gradientEndArgb)),
                ),
            ),
    ) {
        AsyncImage(
            model = requestOf(item.coverGallery),
            contentDescription = item.displayTitle,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.08f),
                        0.45f to Color.Black.copy(alpha = 0.25f),
                        1.0f to Color.Black.copy(alpha = 0.78f),
                    ),
                ),
        )
        Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color.Black.copy(alpha = 0.58f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Whatshot,
                    contentDescription = null,
                    tint = Color(0xFFFF8A3D),
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "${stringResource(R.string.search_rec_hero_badge)} · $starBadge",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = item.displayTitle,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = item.subtitle,
                color = Color.White.copy(alpha = 0.88f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SquareRecommendedTagCard(
    item: RecommendedTagItem,
    onClick: () -> Unit,
) {
    val starText = remember(item.coverGallery.rating) {
        String.format(Locale.US, "★%.1f", item.coverGallery.rating.coerceIn(0f, 5f))
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(
                Brush.verticalGradient(
                    listOf(Color(item.gradientStartArgb), Color(item.gradientEndArgb)),
                ),
            ),
    ) {
        AsyncImage(
            model = requestOf(item.coverGallery),
            contentDescription = item.displayTitle,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.05f),
                        0.38f to Color.Black.copy(alpha = 0.18f),
                        1.0f to Color.Black.copy(alpha = 0.78f),
                    ),
                ),
        )
        Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
            shape = RoundedCornerShape(8.dp),
            color = Color.Black.copy(alpha = 0.56f),
        ) {
            Text(
                text = starText,
                color = Color(0xFFFFD580),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = item.displayTitle,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.rawTag,
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RecommendedArtistCard(
    item: RecommendedTagItem,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(item.gradientStartArgb), Color(item.gradientEndArgb)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = requestOf(item.coverGallery),
                    contentDescription = item.displayTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayTitle.removePrefix("#"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.sampleCount > 1) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = "${item.sampleCount}作",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecommendListFooter(
    itemCount: Int,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isLoadingMore -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.search_rec_loading_more),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            hasMore -> {
                TextButton(onClick = onLoadMore) {
                    Text(text = stringResource(R.string.search_rec_load_more))
                }
            }
            else -> {
                Text(
                    text = stringResource(R.string.search_rec_refresh_hint, itemCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FilterSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
