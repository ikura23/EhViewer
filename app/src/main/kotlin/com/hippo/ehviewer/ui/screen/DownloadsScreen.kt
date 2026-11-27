package com.hippo.ehviewer.ui.screen

import android.content.Context
import android.content.Intent
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NewLabel
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.fork.SwipeToDismissBox
import androidx.compose.material3.fork.SwipeToDismissBoxState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import arrow.core.partially1
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.TagNamespace
import com.ehviewer.core.ui.component.CrystalCard
import com.ehviewer.core.ui.component.FAB_ANIMATE_TIME
import com.ehviewer.core.ui.component.FabLayout
import com.ehviewer.core.ui.component.FastScrollLazyColumn
import com.ehviewer.core.ui.component.FastScrollLazyVerticalStaggeredGrid
import com.ehviewer.core.ui.component.LocalSideSheetState
import com.ehviewer.core.ui.component.ProvideSideSheetContent
import com.ehviewer.core.ui.component.RollingNumber
import com.ehviewer.core.ui.component.RollingNumberPlaceholder
import com.ehviewer.core.ui.icons.EhIcons
import com.ehviewer.core.ui.icons.big.Download
import com.ehviewer.core.ui.util.HapticFeedbackType
import com.ehviewer.core.ui.util.asyncState
import com.ehviewer.core.ui.util.ifTrueThen
import com.ehviewer.core.ui.util.rememberHapticFeedback
import com.ehviewer.core.ui.util.rememberInVM
import com.ehviewer.core.ui.util.takeAndClear
import com.ehviewer.core.ui.util.thenIf
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.ehviewer.core.util.mapToLongArray
import com.ehviewer.core.util.onEachLatest
import com.ehviewer.core.util.withNonCancellableContext
import com.ehviewer.core.util.withUIContext
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.client.EhTagDatabase
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.DownloadService
import com.hippo.ehviewer.download.DownloadsFilterMode
import com.hippo.ehviewer.download.GalleryUpdateProgress
import com.hippo.ehviewer.download.GalleryUpdateTracker
import com.hippo.ehviewer.download.LocalGalleryScanner
import com.hippo.ehviewer.download.DuplicateGalleryGroup
import com.hippo.ehviewer.download.DuplicateGalleryIgnoreStore
import com.hippo.ehviewer.download.SortMode
import com.hippo.ehviewer.download.UpdateStage
import com.hippo.ehviewer.download.canMergeOlderVersionToNewer
import com.hippo.ehviewer.download.findDuplicateGalleryGroups
import com.hippo.ehviewer.ui.DrawerHandle
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.confirmRemoveDownloadRange
import com.hippo.ehviewer.ui.main.DownloadCard
import com.hippo.ehviewer.ui.main.GalleryInfoGridItem
import com.hippo.ehviewer.ui.navToReader
import com.hippo.ehviewer.ui.showMoveDownloadLabelList
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.hippo.ehviewer.ui.tools.awaitInputText
import com.hippo.ehviewer.ui.tools.awaitSelectAction
import com.hippo.ehviewer.ui.tools.awaitSelectItemWithCheckBox
import com.hippo.ehviewer.ui.tools.awaitSingleChoice
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch as coroutineLaunch
import moe.tarsin.navigate
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.DownloadsScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    var gridView by Settings.gridView.asMutableState()
    var sortMode by Settings.downloadSortMode.asMutableState()
    val filterMode by Settings.downloadFilterMode.collectAsState { DownloadsFilterMode.from(it) }
    val showProgress by Settings.showReadingProgress.collectAsState()
    var filterState by rememberSerializable { mutableStateOf(DownloadsFilterState(filterMode, Settings.recentDownloadLabel.value)) }
    var invalidateKey by rememberSaveable { mutableStateOf(false) }
    var isLoading by rememberSaveable { mutableStateOf(true) }
    var searchBarExpanded by rememberSaveable { mutableStateOf(false) }
    var searchBarOffsetY by remember { mutableIntStateOf(0) }
    val animateItems by Settings.animateItems.collectAsState()
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current
    val appResources = LocalResources.current
    val metadataRevision = DownloadManager.metadataRevision
    val scanProgress by LocalGalleryScanner.scanProgress.collectAsState()
    val updateProgress by GalleryUpdateTracker.progress.collectAsState()
    val ignoredDuplicateKeys by DuplicateGalleryIgnoreStore.ignoredKeys.collectAsState()
    val showTagTranslations by Settings.showTagTranslations.collectAsState()

    LaunchedEffect(showTagTranslations, EhTagDatabase.initialized) {
        if (showTagTranslations && EhTagDatabase.translatable && !EhTagDatabase.initialized) {
            EhTagDatabase.ensureInitialized()
        }
    }
    LaunchedEffect(scanProgress.completedEventId, metadataRevision, updateProgress.finishedEventId) {
        if (scanProgress.completedEventId > 0 || metadataRevision > 0 || updateProgress.finishedEventId > 0) {
            invalidateKey = !invalidateKey
        }
    }
    val folderScanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        launchIO {
            if (!LocalGalleryScanner.hasAllFilesAccess()) {
                scope.coroutineLaunch {
                    Toast.makeText(
                        appContext,
                        appResources.getString(R.string.download_scan_local_permission),
                        Toast.LENGTH_LONG,
                    ).show()
                    LocalGalleryScanner.openAllFilesAccessSettings(appContext)
                }
                return@launchIO
            }
            val folder = LocalGalleryScanner.resolveTreePath(uri)
            if (folder == null) {
                scope.coroutineLaunch {
                    Toast.makeText(
                        appContext,
                        appResources.getString(R.string.download_scan_folder_invalid),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                return@launchIO
            }
            val started = LocalGalleryScanner.launchScanInBackground(
                extraRoots = listOf(folder),
                userInitiated = true,
            )
            scope.coroutineLaunch {
                Toast.makeText(
                    appContext,
                    appResources.getString(if (started) R.string.download_scan_started else R.string.download_scan_running),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun triggerLocalScan() {
        if (!LocalGalleryScanner.hasAllFilesAccess()) {
            Toast.makeText(
                appContext,
                appResources.getString(R.string.download_scan_local_permission),
                Toast.LENGTH_LONG,
            ).show()
            LocalGalleryScanner.openAllFilesAccessSettings(appContext)
        } else {
            val started = LocalGalleryScanner.launchScanInBackground(userInitiated = true)
            Toast.makeText(
                appContext,
                appResources.getString(if (started) R.string.download_scan_started else R.string.download_scan_running),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun triggerFolderScan() {
        if (!LocalGalleryScanner.hasAllFilesAccess()) {
            Toast.makeText(
                appContext,
                appResources.getString(R.string.download_scan_local_permission),
                Toast.LENGTH_LONG,
            ).show()
            LocalGalleryScanner.openAllFilesAccessSettings(appContext)
        } else {
            folderScanLauncher.launch(null)
        }
    }

    var fabExpanded by remember { mutableStateOf(false) }
    var fabHidden by remember { mutableStateOf(false) }
    val checkedInfoMap = remember { mutableStateMapOf<Long, DownloadInfo>() }
    val selectMode by rememberUpdatedState(checkedInfoMap.isNotEmpty())
    DrawerHandle(!selectMode && !searchBarExpanded)

    val density = LocalDensity.current
    val canTranslate = showTagTranslations && EhTagDatabase.translatable && EhTagDatabase.initialized
    val ehTags = EhTagDatabase.takeIf { canTranslate }
    fun getTranslation(tag: String) = ehTags?.run {
        getTranslation(TagNamespace.Artist.prefix, tag) ?: getTranslation(TagNamespace.Cosplayer.prefix, tag)
    } ?: tag
    val positionalThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val allName = stringResource(R.string.download_all)
    val defaultName = stringResource(R.string.default_download_label_name)
    val unknownName = stringResource(R.string.unknown_artists)
    val emptyLabelName = when (filterMode) {
        DownloadsFilterMode.ARTIST -> unknownName
        DownloadsFilterMode.CUSTOM -> defaultName
    }
    val title = stringResource(
        R.string.scene_download_title,
        with(filterState) {
            when (label) {
                "" -> allName
                null -> emptyLabelName
                else -> if (mode == DownloadsFilterMode.ARTIST) getTranslation(label) else label
            }
        },
    )
    val hint = stringResource(R.string.search_bar_hint, title)
    val list = if (DownloadManager.isInitialized) {
        remember(filterState, invalidateKey, metadataRevision) {
            DownloadManager.downloadInfoList.filterTo(mutableStateListOf()) { info ->
                filterState.take(info)
            }.also {
                launch {
                    delay(200)
                    isLoading = false
                }
            }
        }
    } else {
        remember { mutableStateListOf() }
    }

    // 监听下载清单成员增删（如后台增量更新入列新版、完成替换移除旧版），立即刷新当前列表
    LaunchedEffect(filterState, invalidateKey) {
        if (!DownloadManager.isInitialized) return@LaunchedEffect
        DownloadManager.notifyFlow.collect {
            val expectedIds = DownloadManager.downloadInfoList.filter { filterState.take(it) }.mapTo(hashSetOf()) { it.gid }
            val currentIds = list.mapTo(hashSetOf()) { it.gid }
            if (expectedIds != currentIds) {
                invalidateKey = !invalidateKey
            }
        }
    }

    val duplicateGroups = if (DownloadManager.isInitialized) {
        remember(invalidateKey, metadataRevision, ignoredDuplicateKeys, list.size) {
            findDuplicateGalleryGroups(
                items = DownloadManager.downloadInfoList,
                ignoredKeys = ignoredDuplicateKeys,
            )
        }
    } else {
        emptyList()
    }

    fun handleDuplicateGroup(group: DuplicateGalleryGroup) {
        launch {
            val mergeText = appResources.getString(R.string.download_duplicate_action_merge)
            val deleteOldText = appResources.getString(R.string.download_duplicate_action_delete_old)
            val ignoreText = appResources.getString(R.string.download_duplicate_action_ignore_group)
            val primaryOld = group.older.firstOrNull() ?: return@launch
            val canMerge = group.older.size == 1 && canMergeOlderVersionToNewer(primaryOld, group.newest)
            val selectedAction = awaitSelectAction(title = R.string.download_duplicate_dialog_title) {
                if (canMerge) {
                    onSelect(mergeText) {
                        val started = GalleryUpdateTracker.launchIncrementalUpdate(
                            oldInfo = primaryOld,
                            targetGid = group.newest.gid,
                            targetToken = group.newest.token,
                            titleHint = group.displayTitle,
                            targetLabel = group.newest.label ?: primaryOld.label,
                            replaceOld = true,
                        )
                        Toast.makeText(
                            appContext,
                            appResources.getString(
                                if (started) R.string.download_update_started_bg else R.string.download_update_running,
                            ),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                onSelect(deleteOldText) {
                    launchIO {
                        group.older.forEach { oldItem ->
                            DownloadManager.deleteOlderVersionKeepNewer(oldItem.gid, group.newest.gid)
                        }
                        scope.coroutineLaunch {
                            invalidateKey = !invalidateKey
                            Toast.makeText(
                                appContext,
                                appResources.getString(R.string.download_duplicate_deleted_toast),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
                onSelect(ignoreText) {
                    DuplicateGalleryIgnoreStore.ignore(group.key)
                }
            }
            selectedAction()
        }
    }

    val newLabel = stringResource(R.string.new_label_title)
    val renameLabel = stringResource(R.string.rename_label_title)
    val labelsStr = stringResource(R.string.download_labels)
    val labelEmpty = stringResource(R.string.label_text_is_empty)
    val defaultInvalid = stringResource(R.string.label_text_is_invalid)
    val labelExists = stringResource(R.string.label_text_exist)
    val downloadsCountGroupByArtist by rememberInVM { EhDB.downloadsCountByArtist }.collectAsState(emptyMap())
    val downloadsCountGroupByLabel by rememberInVM { EhDB.downloadsCountByLabel }.collectAsState(emptyMap())
    val downloadsCount = when (filterMode) {
        DownloadsFilterMode.CUSTOM -> downloadsCountGroupByLabel
        DownloadsFilterMode.ARTIST -> downloadsCountGroupByArtist
    }
    val artistList = remember(downloadsCountGroupByArtist) {
        downloadsCountGroupByArtist.keys.mapNotNull { artist -> artist?.let { it to it } }
    }
    val labelList by remember {
        derivedStateOf {
            DownloadManager.labelList.map { it.id!! to it.label }
        }
    }
    val groupList = when (filterMode) {
        DownloadsFilterMode.CUSTOM -> labelList
        DownloadsFilterMode.ARTIST -> artistList
    }
    val totalCount = remember(downloadsCountGroupByLabel) { downloadsCountGroupByLabel.values.sum() }

    fun switchLabel(label: String?) {
        Settings.recentDownloadLabel.value = label
        filterState = filterState.copy(label = label)
        fabHidden = false
    }

    LaunchedEffect(filterState) {
        searchBarOffsetY = 0
    }

    ProvideSideSheetContent { drawerState ->
        fun closeSheet() = launch { drawerState.close() }
        TopAppBar(
            title = { Text(text = labelsStr) },
            windowInsets = WindowInsets(),
            colors = topBarOnDrawerColor(),
            actions = {
                if (DownloadsFilterMode.CUSTOM == filterMode) {
                    IconButton(
                        onClick = {
                            launch {
                                val text = awaitInputText(title = newLabel, hint = labelsStr) { text ->
                                    when {
                                        text.isBlank() -> raise(labelEmpty)
                                        text == defaultName -> raise(defaultInvalid)
                                        DownloadManager.containLabel(text) -> raise(labelExists)
                                    }
                                }
                                DownloadManager.addLabel(text)
                            }
                        },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(imageVector = Icons.Default.NewLabel, contentDescription = null)
                    }
                    val letMeSelect = stringResource(R.string.let_me_select)
                    IconButton(
                        onClick = {
                            launch {
                                val selected = if (!Settings.hasDefaultDownloadLabel) {
                                    0
                                } else {
                                    DownloadManager.labelList.indexOfFirst { it.label == Settings.defaultDownloadLabel } + 2
                                }
                                awaitSelectAction(R.string.default_download_label, selected) {
                                    onSelect(letMeSelect) {
                                        Settings.hasDefaultDownloadLabel = false
                                    }
                                    onSelect(defaultName) {
                                        Settings.hasDefaultDownloadLabel = true
                                        Settings.defaultDownloadLabel = null
                                    }
                                    DownloadManager.labelList.forEach { (label) ->
                                        onSelect(label) {
                                            Settings.hasDefaultDownloadLabel = true
                                            Settings.defaultDownloadLabel = label
                                        }
                                    }
                                }()
                            }
                        },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(imageVector = Icons.Default.Download, contentDescription = null)
                    }
                }
                val custom = stringResource(R.string.select_grouping_mode_custom)
                val artist = stringResource(R.string.select_grouping_mode_artist)
                IconButton(
                    onClick = {
                        launch {
                            awaitSelectAction(R.string.select_grouping_mode) {
                                val select = { mode: DownloadsFilterMode ->
                                    filterState = filterState.copy(mode = mode, label = "")
                                    Settings.downloadFilterMode.value = mode.flag
                                    Settings.recentDownloadLabel.value = ""
                                }
                                onSelect(custom) { select(DownloadsFilterMode.CUSTOM) }
                                onSelect(artist) { select(DownloadsFilterMode.ARTIST) }
                            }()
                        }
                    },
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = null)
                }
            },
        )

        val dialogState by rememberUpdatedState(contextOf<DialogState>())
        val labelsListState = rememberLazyListState()
        val editEnable = DownloadsFilterMode.CUSTOM == filterMode
        val hapticFeedback = rememberHapticFeedback()
        val reorderableLabelState = rememberReorderableLazyListState(labelsListState) { from, to ->
            val fromPosition = from.index - 2
            val toPosition = to.index - 2
            DownloadManager.labelList.apply { add(toPosition, removeAt(fromPosition)) }
            hapticFeedback.performHapticFeedback(HapticFeedbackType.MOVE)
        }
        var fromIndex by remember { mutableIntStateOf(-1) }
        FastScrollLazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            state = labelsListState,
            contentPadding = WindowInsets.systemBars.only(WindowInsetsSides.Bottom).asPaddingValues(),
        ) {
            item {
                ListItem(
                    modifier = Modifier.clip(CardDefaults.shape).clickable {
                        switchLabel("")
                        closeSheet()
                    },
                    shadowElevation = 1.dp,
                    headlineContent = {
                        Text("$allName [$totalCount]")
                    },
                    colors = listItemOnDrawerColor(filterState.label == ""),
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clip(CardDefaults.shape).clickable {
                        switchLabel(null)
                        closeSheet()
                    },
                    shadowElevation = 1.dp,
                    headlineContent = {
                        Text("$emptyLabelName [${downloadsCount.getOrDefault(null, 0)}]")
                    },
                    colors = listItemOnDrawerColor(filterState.label == null),
                )
            }

            itemsIndexed(groupList, key = { _, (id) -> id }) { itemIndex, (id, label) ->
                val index by rememberUpdatedState(itemIndex)
                val item by rememberUpdatedState(label)
                ReorderableItem(
                    reorderableLabelState,
                    id,
                    enabled = editEnable,
                    animateItemModifier = Modifier.thenIf(animateItems) { animateItem() },
                ) { isDragging ->
                    // Not using rememberSwipeToDismissBoxState to prevent LazyColumn from reusing it
                    // SQLite may reuse ROWIDs from previously deleted rows so they'll have the same key
                    val dismissState = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold) }
                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {},
                        enableDismissFromStartToEnd = false,
                        gesturesEnabled = editEnable,
                        onDismiss = {
                            dialogState.runCatching {
                                awaitConfirmationOrCancel(confirmText = R.string.delete) {
                                    Text(text = stringResource(R.string.delete_label, item))
                                }
                            }.onSuccess {
                                DownloadManager.deleteLabel(item)
                                when (filterState.label) {
                                    item -> switchLabel("")
                                    null -> invalidateKey = !invalidateKey
                                }
                            }.onFailure {
                                dismissState.reset()
                            }
                        },
                    ) {
                        val elevation by animateDpAsState(
                            if (isDragging) {
                                8.dp // md.sys.elevation.level4
                            } else {
                                1.dp // md.sys.elevation.level1
                            },
                            label = "elevation",
                        )
                        ListItem(
                            modifier = Modifier.clip(CardDefaults.shape).clickable {
                                switchLabel(item)
                                closeSheet()
                            },
                            shadowElevation = elevation,
                            headlineContent = {
                                val name = if (filterMode == DownloadsFilterMode.ARTIST) getTranslation(label) else label
                                Text("$name [${downloadsCount.getOrDefault(item, 0)}]")
                            },
                            trailingContent = editEnable.ifTrueThen {
                                Row {
                                    IconButton(
                                        onClick = {
                                            launch {
                                                val new = awaitInputText(initial = item, title = renameLabel, hint = labelsStr) { text ->
                                                    when {
                                                        text.isBlank() -> raise(labelEmpty)
                                                        text == defaultName -> raise(defaultInvalid)
                                                        DownloadManager.containLabel(text) -> raise(labelExists)
                                                    }
                                                }
                                                DownloadManager.renameLabel(item, new)
                                                if (filterState.label == item) {
                                                    switchLabel(new)
                                                }
                                            }
                                        },
                                        shapes = IconButtonDefaults.shapes(),
                                    ) {
                                        Icon(imageVector = Icons.Default.Edit, contentDescription = null)
                                    }
                                    IconButton(
                                        onClick = {},
                                        shapes = IconButtonDefaults.shapes(),
                                        modifier = Modifier.draggableHandle(
                                            onDragStarted = {
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.START)
                                                fromIndex = index
                                            },
                                            onDragStopped = {
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.END)
                                                if (fromIndex != -1) {
                                                    if (fromIndex != index) {
                                                        val range = if (fromIndex < index) fromIndex..index else index..fromIndex
                                                        val toUpdate = DownloadManager.labelList.slice(range)
                                                        toUpdate.zip(range).forEach { it.first.position = it.second }
                                                        launchIO { EhDB.updateDownloadLabel(toUpdate) }
                                                    }
                                                    fromIndex = -1
                                                }
                                            },
                                        ),
                                    ) {
                                        Icon(imageVector = Icons.Default.Reorder, contentDescription = null)
                                    }
                                }
                            },
                            colors = listItemOnDrawerColor(filterState.label == item),
                        )
                    }
                }
            }
        }
    }

    val searchFieldState = rememberTextFieldState()
    class DownloadLabelSuggestion(private val label: String) : Suggestion() {
        override val keyword = LABEL_PREFIX + label
        override fun onClick() {
            searchFieldState.setTextAndPlaceCursorAtEnd(keyword)
            searchBarExpanded = false
            switchLabel(label)
        }
    }
    SearchBarScreen(
        onApplySearch = { filterState = filterState.copy(keyword = it) },
        expanded = searchBarExpanded,
        onExpandedChange = {
            searchBarExpanded = it
            fabHidden = it
            if (it) checkedInfoMap.clear()
        },
        title = title,
        searchFieldHint = hint,
        searchFieldState = searchFieldState,
        suggestionProvider = { query ->
            val label = query.substringAfter(LABEL_PREFIX, "")
            if (label.isEmpty()) {
                emptyList()
            } else {
                EhDB.searchDownloadLabel(label, 10).map(::DownloadLabelSuggestion)
            }
        },
        searchBarOffsetY = { searchBarOffsetY },
        trailingIcon = {
            var expanded by remember { mutableStateOf(false) }
            val sideSheetState = LocalSideSheetState.current
            IconButton(onClick = { gridView = !gridView }, shapes = IconButtonDefaults.shapes()) {
                val icon = if (gridView) Icons.AutoMirrored.Default.ViewList else Icons.Default.GridView
                Icon(imageVector = icon, contentDescription = null)
            }
            IconButton(onClick = { expanded = !expanded }, shapes = IconButtonDefaults.shapes()) {
                Icon(imageVector = Icons.Default.MoreVert, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(id = R.string.download_labels)) },
                    onClick = {
                        expanded = false
                        launch { sideSheetState.open() }
                    },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(id = R.string.download_scan_local)) },
                    onClick = {
                        expanded = false
                        triggerLocalScan()
                    },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(id = R.string.download_scan_folder)) },
                    onClick = {
                        expanded = false
                        triggerFolderScan()
                    },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(id = R.string.download_start_all)) },
                    onClick = {
                        expanded = false
                        DownloadService.startService(DownloadService.ACTION_START_ALL)
                    },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(id = R.string.download_stop_all)) },
                    onClick = {
                        expanded = false
                        launchIO { DownloadManager.stopAllDownload() }
                    },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(id = R.string.download_reset_reading_progress)) },
                    onClick = {
                        expanded = false
                        launchIO {
                            awaitConfirmationOrCancel(
                                confirmText = android.R.string.ok,
                                dismissText = android.R.string.cancel,
                            ) {
                                Text(text = stringResource(id = R.string.reset_reading_progress_message))
                            }
                            withNonCancellableContext {
                                DownloadManager.resetAllReadingProgress()
                            }
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(id = R.string.download_start_all_reversed)) },
                    onClick = {
                        expanded = false
                        val gidList = list.filter { it.state != DownloadInfo.STATE_FINISH }.asReversed().mapToLongArray(DownloadInfo::gid)
                        DownloadService.startRangeDownload(gidList)
                    },
                )
            }
        },
    ) { contentPadding ->
        val height by collectListThumbSizeAsState()
        val realPadding = contentPadding + PaddingValues(dimensionResource(id = com.hippo.ehviewer.R.dimen.gallery_list_margin_h), dimensionResource(id = com.hippo.ehviewer.R.dimen.gallery_list_margin_v))
        val searchBarConnection = remember {
            val slop = ViewConfiguration.get(contextOf<Context>()).scaledTouchSlop
            val topPaddingPx = with(density) { contentPadding.calculateTopPadding().roundToPx() }
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    val dy = -consumed.y
                    if (dy >= slop) {
                        fabHidden = true
                    } else if (dy <= -slop / 2) {
                        fabHidden = false
                    }
                    searchBarOffsetY = (searchBarOffsetY - dy).roundToInt().coerceIn(-topPaddingPx, 0)
                    return Offset.Zero // We never consume it
                }
            }
        }

        fun onItemClick(info: DownloadInfo) {
            launchIO { EhDB.putHistoryInfo(info) }
            navToReader(info.galleryInfo)
        }

        Crossfade(targetState = gridView, label = "Downloads") { showGridView ->
            if (showGridView) {
                val gridInterval = dimensionResource(com.hippo.ehviewer.R.dimen.gallery_grid_interval)
                val thumbColumns by Settings.thumbColumns.collectAsState()
                FastScrollLazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(thumbColumns),
                    modifier = Modifier.nestedScroll(searchBarConnection).fillMaxSize(),
                    contentPadding = realPadding,
                    verticalItemSpacing = gridInterval,
                    horizontalArrangement = Arrangement.spacedBy(gridInterval),
                ) {
                    if (scanProgress.running && scanProgress.lastRunUserInitiated) {
                        item(key = "local-scan-progress", span = StaggeredGridItemSpan.FullLine) {
                            LocalScanProgressCard(
                                progress = scanProgress,
                                onCancel = LocalGalleryScanner::cancelScan,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                    if (updateProgress.running) {
                        item(key = "gallery-update-progress", span = StaggeredGridItemSpan.FullLine) {
                            GalleryUpdateProgressCard(
                                progress = updateProgress,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                    if (duplicateGroups.isNotEmpty() && !selectMode) {
                        item(key = "duplicate-gallery-banner", span = StaggeredGridItemSpan.FullLine) {
                            DuplicateGalleryBannerCard(
                                groups = duplicateGroups,
                                onHandle = ::handleDuplicateGroup,
                                onIgnore = { DuplicateGalleryIgnoreStore.ignore(it.key) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                    items(list, key = { it.gid }) { info ->
                        GalleryInfoGridItem(
                            onClick = ::onItemClick.partially1(info),
                            onLongClick = { navigate(info.galleryInfo.asDst()) },
                            info = info,
                            modifier = Modifier.thenIf(animateItems) { animateItem() },
                            showLanguage = false,
                            showProgress = showProgress,
                        )
                    }
                }
            } else {
                val listState = rememberLazyListState()
                val isManualSort = SortMode.from(sortMode).field == SortMode.Field.CUSTOM
                val listHapticFeedback = rememberHapticFeedback()
                val reorderableListState = rememberReorderableLazyListState(listState) { from, to ->
                    val fromIdx = list.indexOfFirst { it.gid == from.key }
                    val toIdx = list.indexOfFirst { it.gid == to.key }
                    if (fromIdx >= 0 && toIdx >= 0 && fromIdx != toIdx) {
                        list.apply { add(toIdx, removeAt(fromIdx)) }
                        listHapticFeedback.performHapticFeedback(HapticFeedbackType.MOVE)
                    }
                }
                FastScrollLazyColumn(
                    modifier = Modifier.nestedScroll(searchBarConnection).fillMaxSize(),
                    state = listState,
                    contentPadding = realPadding,
                    verticalArrangement = Arrangement.spacedBy(dimensionResource(com.hippo.ehviewer.R.dimen.gallery_list_interval)),
                ) {
                    if (scanProgress.running && scanProgress.lastRunUserInitiated) {
                        item(key = "local-scan-progress") {
                            LocalScanProgressCard(
                                progress = scanProgress,
                                onCancel = LocalGalleryScanner::cancelScan,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                    if (updateProgress.running) {
                        item(key = "gallery-update-progress") {
                            GalleryUpdateProgressCard(
                                progress = updateProgress,
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                    if (duplicateGroups.isNotEmpty() && !selectMode) {
                        item(key = "duplicate-gallery-banner") {
                            DuplicateGalleryBannerCard(
                                groups = duplicateGroups,
                                onHandle = ::handleDuplicateGroup,
                                onIgnore = { DuplicateGalleryIgnoreStore.ignore(it.key) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                    itemsIndexed(list, key = { _, info -> info.gid }) { _, info ->
                        val checked = info.gid in checkedInfoMap
                        ReorderableItem(
                            reorderableListState,
                            info.gid,
                            enabled = isManualSort && !selectMode,
                            animateItemModifier = Modifier.thenIf(animateItems) { animateItem() },
                        ) { isDragging ->
                            val dragModifier = if (isManualSort && !selectMode) {
                                Modifier.draggableHandle(
                                    onDragStarted = {
                                        listHapticFeedback.performHapticFeedback(HapticFeedbackType.START)
                                    },
                                    onDragStopped = {
                                        listHapticFeedback.performHapticFeedback(HapticFeedbackType.END)
                                        val orderedGids = list.map(DownloadInfo::gid)
                                        launchIO { DownloadManager.updateManualOrder(orderedGids) }
                                    },
                                )
                            } else {
                                null
                            }
                            CheckableItem(checked = checked) { interactionSource ->
                                DownloadCard(
                                    onClick = {
                                        if (selectMode) {
                                            if (checked) {
                                                checkedInfoMap.remove(info.gid)
                                            } else {
                                                checkedInfoMap[info.gid] = info
                                            }
                                        } else {
                                            onItemClick(info)
                                        }
                                    },
                                    onThumbClick = {
                                        navigate(info.galleryInfo.asDst())
                                    },
                                    onLongClick = {
                                        checkedInfoMap[info.gid] = info
                                    },
                                    onStart = {
                                        DownloadService.startDownload(info.galleryInfo)
                                    },
                                    onStop = { launchIO { DownloadManager.stopDownload(info.gid) } },
                                    info = info,
                                    selectMode = selectMode,
                                    showProgress = showProgress,
                                    thumbnailRefreshKey = info.thumbKey ?: info.dirname ?: Unit,
                                    dragHandleModifier = dragModifier,
                                    isDragging = isDragging,
                                    modifier = Modifier.height(height),
                                    interactionSource = interactionSource,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                CircularWavyProgressIndicator()
            }
        } else if (list.isEmpty() && !updateProgress.running && !scanProgress.running) {
            Column(
                modifier = Modifier.padding(realPadding).fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = EhIcons.Big.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.padding(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(id = R.string.no_download_info),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = ::triggerLocalScan) {
                        Text(text = stringResource(id = R.string.download_scan_local))
                    }
                    OutlinedButton(onClick = ::triggerFolderScan) {
                        Text(text = stringResource(id = R.string.download_scan_folder))
                    }
                }
            }
        }
    }

    val hideFab by asyncState(
        produce = { fabHidden },
        transform = {
            onEachLatest { hide ->
                if (!hide) delay(FAB_ANIMATE_TIME.toLong())
            }
        },
    )

    FabLayout(
        hidden = hideFab && !selectMode,
        expanded = fabExpanded || selectMode,
        onExpandChanged = {
            fabExpanded = it
            checkedInfoMap.clear()
        },
        autoCancel = !selectMode,
    ) {
        if (!selectMode) {
            onClick(Icons.Default.Shuffle) {
                if (list.isNotEmpty()) {
                    withUIContext { navToReader(list.random().galleryInfo) }
                }
            }
            onClick(Icons.AutoMirrored.Default.Sort) {
                val oldMode = SortMode.from(sortMode)
                val sortModes = contextOf<Context>().resources.getStringArray(com.hippo.ehviewer.R.array.download_sort_modes).toList()
                val (selected, checked) = awaitSelectItemWithCheckBox(
                    sortModes,
                    R.string.sort_by,
                    R.string.group_by_download_label,
                    SortMode.All.indexOfFirst { it.field == oldMode.field && it.order == oldMode.order },
                    oldMode.groupByDownloadLabel,
                )
                val mode = SortMode.All[selected].copy(groupByDownloadLabel = checked)
                if (mode != oldMode) {
                    sortMode = mode.flag
                    if (mode.field == SortMode.Field.CUSTOM && gridView) {
                        gridView = false
                        Toast.makeText(
                            appContext,
                            appResources.getString(R.string.download_manual_sort_list_hint),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    isLoading = true
                    DownloadManager.sortDownloads(mode)
                    invalidateKey = !invalidateKey
                }
            }
            onClick(Icons.Default.FilterList) {
                val downloadStates = contextOf<Context>().resources.getStringArray(com.hippo.ehviewer.R.array.download_state).toList()
                val state = awaitSingleChoice(
                    downloadStates,
                    filterState.state + 1,
                    R.string.download_filter,
                ) - 1
                filterState = filterState.copy(state = state)
            }
        } else {
            onClick(Icons.Default.DoneAll, autoClose = false) {
                val info = list.associateBy { it.gid }
                checkedInfoMap.putAll(info)
            }
            onClick(Icons.Default.PlayArrow) {
                val gidList = checkedInfoMap.takeAndClear().mapToLongArray(DownloadInfo::gid)
                DownloadService.startRangeDownload(gidList)
            }
            onClick(Icons.Default.Pause) {
                val gidList = checkedInfoMap.takeAndClear().mapToLongArray(DownloadInfo::gid)
                DownloadManager.stopRangeDownload(gidList)
            }
            onClick(Icons.Default.Delete) {
                val infoList = checkedInfoMap.takeAndClear()
                confirmRemoveDownloadRange(infoList)
                list.removeAll(infoList)
            }
            onClick(Icons.AutoMirrored.Default.DriveFileMove) {
                val infoList = checkedInfoMap.takeAndClear()
                val toLabel = showMoveDownloadLabelList(infoList)
                with(filterState) {
                    if (label != "" && label != toLabel) {
                        list.removeAll(infoList)
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalScanProgressCard(
    progress: LocalGalleryScanner.ScanProgress,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val statusText = when (progress.stage) {
        LocalGalleryScanner.ScanStage.FETCHING_METADATA -> stringResource(
            R.string.download_scan_progress_metadata,
            progress.scanned,
            progress.total,
        )
        else -> stringResource(
            R.string.download_scan_progress_scanning,
            progress.scanned,
            progress.total,
        )
    }
    AnimatedVisibility(
        visible = shown,
        enter = expandVertically(tween(220)) + fadeIn(tween(180)),
        modifier = modifier,
    ) {
        CrystalCard(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = statusText,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    TextButton(onClick = onCancel) {
                        Text(text = stringResource(id = R.string.action_cancel))
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                if (progress.total > 0) {
                    val animatedProgress by animateFloatAsState(
                        targetValue = (progress.scanned.toFloat() / progress.total).coerceIn(0f, 1f),
                        animationSpec = tween(durationMillis = 220),
                        label = "LocalScanProgress",
                    )
                    LinearWavyProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun GalleryUpdateProgressCard(
    progress: GalleryUpdateProgress,
    modifier: Modifier = Modifier,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val stageText = when (progress.stage) {
        UpdateStage.FETCHING_DETAIL -> stringResource(R.string.download_update_stage_detail)
        UpdateStage.COMPARING_PAGES -> if (progress.total > 0) {
            stringResource(R.string.download_update_stage_compare, progress.current, progress.total)
        } else {
            stringResource(R.string.download_update_preparing)
        }
        UpdateStage.COPYING_REUSABLE -> if (progress.total > 0) {
            stringResource(R.string.download_update_stage_copy, progress.current, progress.total)
        } else {
            stringResource(R.string.download_update_preparing)
        }
        UpdateStage.STARTING_DOWNLOAD -> stringResource(R.string.download_update_stage_enqueue)
        UpdateStage.IDLE -> ""
    }
    AnimatedVisibility(
        visible = shown,
        enter = expandVertically(tween(220)) + fadeIn(tween(180)),
        modifier = modifier,
    ) {
        CrystalCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = progress.title.ifBlank { stringResource(R.string.download_update) },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stageText,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (progress.total > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RollingNumber(
                                number = progress.current,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(text = "/", style = MaterialTheme.typography.labelMedium)
                            RollingNumberPlaceholder(
                                number = progress.total,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                if (progress.total > 0) {
                    val animatedProgress by animateFloatAsState(
                        targetValue = (progress.current.toFloat() / progress.total).coerceIn(0f, 1f),
                        animationSpec = tween(durationMillis = 220),
                        label = "GalleryUpdateProgress",
                    )
                    LinearWavyProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun DuplicateGalleryBannerCard(
    groups: List<DuplicateGalleryGroup>,
    onHandle: (DuplicateGalleryGroup) -> Unit,
    onIgnore: (DuplicateGalleryGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstGroup = groups.firstOrNull() ?: return
    val primaryOld = firstGroup.older.firstOrNull() ?: return
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    AnimatedVisibility(
        visible = shown,
        enter = expandVertically(tween(220)) + fadeIn(tween(180)),
        modifier = modifier,
    ) {
        CrystalCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.download_duplicate_banner_title, groups.size),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = firstGroup.displayTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.download_duplicate_banner_summary,
                        primaryOld.gid,
                        primaryOld.pages,
                        firstGroup.newest.gid,
                        firstGroup.newest.pages,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { onIgnore(firstGroup) }) {
                        Text(text = stringResource(R.string.download_duplicate_action_ignore))
                    }
                    Button(onClick = { onHandle(firstGroup) }) {
                        Text(text = stringResource(R.string.download_duplicate_action_handle))
                    }
                }
            }
        }
    }
}

private const val LABEL_PREFIX = "label:"
