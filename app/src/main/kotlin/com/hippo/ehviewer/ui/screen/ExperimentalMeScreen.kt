package com.hippo.ehviewer.ui.screen

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.FavouritesScreenDestination
import com.hippo.ehviewer.ui.destinations.HistoryScreenDestination
import com.hippo.ehviewer.ui.destinations.HomePageScreenDestination
import com.hippo.ehviewer.ui.destinations.SettingsScreenDestination
import com.hippo.ehviewer.ui.main.AvatarIcon
import com.hippo.ehviewer.ui.main.requestOf
import com.hippo.ehviewer.ui.navToReader
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import moe.tarsin.navigate

private data class ContinueReadingItem(
    val downloadInfo: DownloadInfo,
    val pageIndex: Int,
)

/**
 * 实验性新版「我的」页面：账号/站点切换 + 收藏/历史双入口 + 继续阅读 + 常用显示直通开关 + 随时切回经典界面。
 */
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.ExperimentalMeScreen(
    navigator: DestinationsNavigator,
) = Screen(navigator) {
    val displayName by Settings.displayName.collectAsState()
    val hasSignedIn by Settings.hasSignedIn.collectAsState()
    var gallerySite by Settings.gallerySite.asMutableState()
    var listMode by Settings.listMode.asMutableState()
    var thumbColumns by Settings.thumbColumns.asMutableState()
    var showPages by Settings.showGalleryPages.asMutableState()
    var showProgress by Settings.showReadingProgress.asMutableState()
    var showTagTranslations by Settings.showTagTranslations.asMutableState()
    var nonHMode by Settings.nonHMode.asMutableState()
    var experimentalNewUi by Settings.experimentalNewUi.asMutableState()

    val recentReading by produceState<List<ContinueReadingItem>>(emptyList()) {
        value = withIOContext {
            DownloadManager.downloadInfoList
                .filter { it.state == DownloadInfo.STATE_FINISH }
                .take(40)
                .mapNotNull { info ->
                    val page = EhDB.getReadProgress(info.gid)
                    if (page > 0) ContinueReadingItem(info, page) else null
                }
                .take(8)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.nav_tab_me),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        // 账号 + 站点切换卡 + 头像配额按钮
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarIcon()
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName ?: stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            !hasSignedIn -> stringResource(R.string.guest_mode)
                            gallerySite == 1 -> "ExHentai"
                            else -> "E-Hentai"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (hasSignedIn) {
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(
                            selected = gallerySite == 0,
                            onClick = { gallerySite = 0 },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                            icon = {},
                            label = { Text(text = "E", maxLines = 1) },
                        )
                        SegmentedButton(
                            selected = gallerySite == 1,
                            onClick = { gallerySite = 1 },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                            icon = {},
                            label = { Text(text = "EX", maxLines = 1) },
                        )
                    }
                }
            }
        }

        // 「我的收藏」与「阅读历史」双入口快捷卡
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { navigate(FavouritesScreenDestination) },
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.me_quick_favorites),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.favourite),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { navigate(HistoryScreenDestination) },
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.me_quick_history),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.history),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 继续阅读横向条（仅存在已读进度的下载画廊时展示）
        if (recentReading.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.me_continue_reading),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { navigate(HistoryScreenDestination) }) {
                    Text(text = stringResource(R.string.history))
                }
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(end = 4.dp),
            ) {
                items(recentReading, key = { it.downloadInfo.gid }) { item ->
                    val info = item.downloadInfo.galleryInfo
                    val pages = info.pages.coerceAtLeast(1)
                    Column(
                        modifier = Modifier
                            .width(92.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { navToReader(info, item.pageIndex) },
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.68f)
                                .clip(RoundedCornerShape(12.dp)),
                        ) {
                            AsyncImage(
                                model = requestOf(info),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            LinearProgressIndicator(
                                progress = { ((item.pageIndex + 1).toFloat() / pages).coerceIn(0f, 1f) },
                                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = remember(info) { EhUtils.getSuitableTitle(info) },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // 显示设置直通卡
        Text(
            text = stringResource(R.string.me_display_settings),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_eh_list_mode),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(
                            selected = listMode == 0,
                            onClick = { listMode = 0 },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                            icon = {},
                            label = { Text(text = stringResource(R.string.settings_eh_list_mode_detail)) },
                        )
                        SegmentedButton(
                            selected = listMode != 0,
                            onClick = { listMode = 1 },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                            icon = {},
                            label = { Text(text = stringResource(R.string.settings_eh_list_mode_thumb)) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_eh_thumb_columns),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    SingleChoiceSegmentedButtonRow {
                        listOf(2, 3, 4).forEachIndexed { idx, col ->
                            SegmentedButton(
                                selected = thumbColumns == col,
                                onClick = { thumbColumns = col },
                                shape = SegmentedButtonDefaults.itemShape(idx, 3),
                                icon = {},
                                label = { Text(text = col.toString()) },
                            )
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                MeSwitchRow(
                    title = stringResource(R.string.settings_eh_show_gallery_pages),
                    checked = showPages,
                    onCheckedChange = { showPages = it },
                )
                MeSwitchRow(
                    title = stringResource(R.string.settings_eh_show_reading_progress),
                    checked = showProgress,
                    onCheckedChange = { showProgress = it },
                )
                MeSwitchRow(
                    title = stringResource(R.string.settings_eh_show_tag_translations),
                    checked = showTagTranslations,
                    onCheckedChange = { showTagTranslations = it },
                )
                MeSwitchRow(
                    title = stringResource(R.string.settings_eh_non_h_mode),
                    checked = nonHMode,
                    onCheckedChange = { nonHMode = it },
                )
            }
        }

        // 更多设置 + 一键切回经典界面
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navigate(SettingsScreenDestination) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = null)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.settings),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            experimentalNewUi = false
                            navigator.navigate(HomePageScreenDestination) {
                                popUpTo(HomePageScreenDestination) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.Style,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.pref_experimental_new_ui),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(R.string.experimental_switch_back_classic),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = experimentalNewUi,
                        onCheckedChange = { enabled ->
                            experimentalNewUi = enabled
                            if (!enabled) {
                                navigator.navigate(HomePageScreenDestination) {
                                    popUpTo(HomePageScreenDestination) { inclusive = false }
                                    launchSingleTop = true
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MeSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
