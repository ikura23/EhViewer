package com.hippo.ehviewer.ui.reader

import android.content.Context
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.res.colorResource
import arrow.core.Either
import arrow.core.Either.Companion.catch
import arrow.core.raise.ensure
import arrow.core.right
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.ui.util.Await
import com.ehviewer.core.ui.util.asyncInVM
import com.ehviewer.core.ui.util.rememberSystemUiController
import com.ehviewer.core.ui.util.thenIf
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.ehviewer.core.util.unreachable
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.READER_THEME_SMART
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.coil.applyReaderColorFilters
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.archiveFile
import com.hippo.ehviewer.download.downloadDir
import com.hippo.ehviewer.download.isLocal
import com.hippo.ehviewer.gallery.LocalFolderPageLoader
import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.PageLoader
import com.hippo.ehviewer.gallery.PageStatus
import com.hippo.ehviewer.gallery.status
import com.hippo.ehviewer.gallery.statusObserved
import com.hippo.ehviewer.gallery.unblock
import com.hippo.ehviewer.gallery.useArchivePageLoader
import com.hippo.ehviewer.gallery.useEhPageLoader
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.theme.EhTheme
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.awaitInputText
import com.hippo.ehviewer.ui.tools.dialog
import com.hippo.ehviewer.util.displayString
import com.hippo.ehviewer.util.hasAds
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import eu.kanade.tachiyomi.ui.reader.PageIndicatorText
import eu.kanade.tachiyomi.ui.reader.ReaderAppBars
import eu.kanade.tachiyomi.ui.reader.ReaderContentOverlay
import eu.kanade.tachiyomi.ui.reader.ReaderPageSheetMeta
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import moe.tarsin.string
import okio.Path.Companion.toPath

@Serializable
sealed interface ReaderScreenArgs {
    @Serializable
    data class Gallery(val info: BaseGalleryInfo, val page: Int) : ReaderScreenArgs

    @Serializable
    data class Archive(val path: String) : ReaderScreenArgs
}

@Composable
private fun Background(
    color: Color,
    content: @Composable () -> Unit,
) = Box(Modifier.fillMaxSize().background(color), contentAlignment = Alignment.Center) {
    EhTheme(useDarkTheme = !color.isLightColor(), content = content)
}

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.ReaderScreen(args: ReaderScreenArgs, navigator: DestinationsNavigator) = Screen(navigator) {
    val bgColor by collectBackgroundColorAsState()
    val uiController = rememberSystemUiController()
    DisposableEffect(uiController) {
        val lightStatusBar = uiController.statusBarDarkContentEnabled
        uiController.statusBarDarkContentEnabled = bgColor.isLightColor()
        onDispose {
            uiController.statusBarDarkContentEnabled = lightStatusBar
        }
    }

    Await(
        block = asyncInVM(args) { alive ->
            suspendCancellableCoroutine { cont ->
                with(alive) {
                    launchIO {
                        catch {
                            usePageLoader(args) { loader ->
                                cont.resume(loader.right())
                                awaitCancellation()
                            }
                        }.let { left -> cont.resume(left) }
                    }
                }
            }
        }.value.run {
            { await() }
        },
        placeholder = {
            Background(bgColor) {
                CircularWavyProgressIndicator()
            }
        },
    ) { result ->
        when (result) {
            is Either.Left -> Background(bgColor) {
                Text(
                    text = result.value.displayString(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            is Either.Right -> {
                val loader = result.value
                val info = (args as? ReaderScreenArgs.Gallery)?.info
                val isLocalDownloaded = args is ReaderScreenArgs.Archive ||
                    info?.let { DownloadManager.getDownloadInfo(it.gid) != null } == true
                key(loader) {
                    ReaderScreen(loader, info, isLocalDownloaded)
                }
            }
        }
    }
}

@Composable
context(activity: MainActivity, _: SnackbarHostState, _: DialogState, _: CoroutineScope, _: DestinationsNavigator)
fun ReaderScreen(pageLoader: PageLoader, info: BaseGalleryInfo?, isLocalDownloaded: Boolean = false) {
    LaunchedEffect(Unit) {
        val orientation = activity.requestedOrientation
        Settings.orientationMode.valueFlow()
            .onCompletion { activity.requestedOrientation = orientation }
            .collect { activity.setOrientation(it) }
    }
    LaunchedEffect(pageLoader) {
        with(Settings) {
            merge(cropBorder.changesFlow(), stripExtraneousAds.changesFlow()).collect {
                pageLoader.restart()
            }
        }
    }
    val webtoon = remember(info) {
        // Tags in database may or may not have the prefix "other:"
        info?.simpleTags?.any { it.endsWith("webtoon") } == true
    }
    val showSeekbar by Settings.showReaderSeekbar.collectAsState()
    val readingMode by Settings.readingMode.collectAsState {
        when (val mode = ReadingModeType.fromPreference(it)) {
            ReadingModeType.DEFAULT -> if (webtoon) ReadingModeType.WEBTOON else ReadingModeType.RIGHT_TO_LEFT
            else -> mode
        }
    }
    val animatedPlaybackAutoDetect by Settings.animatedPlayback.collectAsState()
    val animatedPlaybackNeighbors by Settings.animatedPlaybackNeighbors.collectAsState()
    val animatedPlaybackPreload by Settings.animatedPlaybackPreload.collectAsState()
    val animatedPlaybackSpeed by Settings.animatedPlaybackSpeed.collectAsState()
    val animatedTag = info?.simpleTags?.any { tag ->
        tag.substringAfter(':').equals("animated", ignoreCase = true)
    } == true
    var animatedLoadingDialogVisible by remember(pageLoader) { mutableStateOf(false) }
    var animatedLoadingDialogShown by remember(pageLoader) { mutableStateOf(false) }
    var loadedAnimatedCount by remember(pageLoader) { mutableIntStateOf(0) }
    // Decoded animation pages are large; keep the dialog window and playback window identical
    // so the heap cannot be exhausted before the first frame even starts.
    val animatedPreloadWindow = animatedPlaybackPreload.coerceIn(1, MAX_ANIMATED_PRELOAD)
    var animatedLoadTarget by remember(pageLoader) {
        mutableIntStateOf(minOf(pageLoader.size.coerceAtLeast(1), animatedPreloadWindow + 1))
    }
    DisposableEffect(pageLoader) {
        val sessionToken = AnimatedPlaybackSession.attach()
        onDispose { AnimatedPlaybackSession.detach(sessionToken) }
    }
    LaunchedEffect(pageLoader, animatedPlaybackAutoDetect) {
        AnimatedPlaybackSession.autoActive = false
        AnimatedPlaybackSession.allAnimated = false
        if (!animatedPlaybackAutoDetect) {
            pageLoader.extraPrefetchCount = 0
            return@LaunchedEffect
        }
        if (animatedTag) {
            pageLoader.extraPrefetchCount = animatedPreloadWindow
            AnimatedPlaybackSession.allAnimated = true
            AnimatedPlaybackSession.autoActive = true
            return@LaunchedEffect
        }
        val size = pageLoader.size
        if (size <= 0) return@LaunchedEffect
        // 避免在主线程遍历磁盘检查扩展名。
        val gifCount = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            (0 until size).count {
                pageLoader.imageExtension(it)?.lowercase()?.endsWith("gif") == true
            }
        }
        if (gifCount * 2 >= size) {
            pageLoader.extraPrefetchCount = animatedPreloadWindow
            AnimatedPlaybackSession.autoActive = true
            return@LaunchedEffect
        }
        // 小窗口增量抽样，避免给普通静态画廊强拉过多页或在超时后破坏阅读缓存。
        val sampleStart = (pageLoader.startPage - 1).coerceAtLeast(0)
        val sampleEnd = (pageLoader.startPage + 5).coerceAtMost(size - 1)
        pageLoader.requestRange(sampleStart, sampleEnd)
        if (sampleAnimatedPages(pageLoader.pages, sampleStart..sampleEnd, 15_000L)) {
            pageLoader.extraPrefetchCount = animatedPreloadWindow
            AnimatedPlaybackSession.autoActive = true
            return@LaunchedEffect
        }
        AnimatedPlaybackSession.autoActive = false
    }
    val animatedPlaybackActive = AnimatedPlaybackSession.active
    LaunchedEffect(animatedPlaybackActive, isLocalDownloaded, pageLoader) {
        if (!animatedPlaybackActive) {
            animatedLoadingDialogVisible = false
            return@LaunchedEffect
        }
        if (!animatedLoadingDialogShown && isLocalDownloaded) {
            animatedLoadingDialogVisible = true
            animatedLoadingDialogShown = true
        }
    }
    LaunchedEffect(pageLoader, animatedLoadingDialogVisible, animatedPlaybackActive, animatedPreloadWindow) {
        if (!animatedLoadingDialogVisible || !animatedPlaybackActive || pageLoader.size <= 0) return@LaunchedEffect
        val start = pageLoader.startPage.coerceIn(0, pageLoader.size - 1)
        val end = minOf(pageLoader.size - 1, start + animatedPreloadWindow)
        val actualTarget = (end - start + 1).coerceAtLeast(1)
        animatedLoadTarget = actualTarget
        pageLoader.requestRange(start, end)
        val completed = withTimeoutOrNull(20_000L) {
            val statusMerged = (start..end)
                .map { idx -> pageLoader.pages[idx].statusFlow }
                .merge()
                .onStart { emit(PageStatus.Queued) }
            combine(
                statusMerged,
                snapshotFlow { AnimatedPlaybackSession.playbackStarted },
            ) { _, started ->
                val done = (start..end).count { pageLoader.pages[it].status.isTerminal() }
                loadedAnimatedCount = done.coerceAtMost(actualTarget)
                done >= actualTarget && started
            }.first { it }
        } == true
        if (!completed) {
            animatedLoadingDialogVisible = false
        }
    }
    val volumeKeysEnabled by Settings.readWithVolumeKeys.collectAsState()
    val volumeKeysInverted by Settings.readWithVolumeKeysInverted.collectAsState()
    val fullscreen by Settings.fullscreen.collectAsState()
    val cutoutShort by Settings.cutoutShort.collectAsState()
    val keepScreenOn by Settings.keepScreenOn.collectAsState()
    val uiController = rememberSystemUiController()
    DisposableEffect(uiController) {
        uiController.showTransientSystemBarsBySwipe = true
        onDispose {
            uiController.isSystemBarsVisible = true
            uiController.showTransientSystemBarsBySwipe = false
        }
    }
    val lazyListState = rememberLazyListState(pageLoader.startPage)
    val pagerState = rememberPagerState(pageLoader.startPage) { pageLoader.size }
    val sync = rememberSliderPagerDoubleSyncState(lazyListState, pagerState, pageLoader)
    var appbarVisible by remember { mutableStateOf(false) }
    val isWebtoon by rememberUpdatedState(
        animatedPlaybackActive || ReadingModeType.isWebtoon(readingMode),
    )
    val focusRequester = remember { FocusRequester() }
    Box(
        Modifier.keyEventHandler(
            volumeKeysEnabled = { volumeKeysEnabled && !appbarVisible },
            volumeKeysInverted = { volumeKeysInverted },
            movePrevious = {
                launch {
                    if (animatedPlaybackActive) {
                        val target = (lazyListState.layoutInfo.dominantVisibleItemIndex(0) - 1).coerceAtLeast(0)
                        lazyListState.animateScrollToItem(target)
                    } else if (isWebtoon) {
                        lazyListState.scrollWebtoonPage(false, pageLoader, Settings.webtoonSidePadding.value)
                    } else {
                        pagerState.moveToPrevious()
                    }
                }
            },
            moveNext = {
                launch {
                    if (animatedPlaybackActive) {
                        val last = (pageLoader.size - 1).coerceAtLeast(0)
                        val target = (lazyListState.layoutInfo.dominantVisibleItemIndex(0) + 1).coerceAtMost(last)
                        lazyListState.animateScrollToItem(target)
                    } else if (isWebtoon) {
                        lazyListState.scrollWebtoonPage(true, pageLoader, Settings.webtoonSidePadding.value)
                    } else {
                        pagerState.moveToNext()
                    }
                }
            },
        ).focusRequester(focusRequester).focusable().thenIf(keepScreenOn) { keepScreenOn() },
    ) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
        sync.Sync(isWebtoon) { appbarVisible = false }
        val fallbackBgColor by collectBackgroundColorAsState()
        val readerTheme by Settings.readerTheme.collectAsState()
        val grayScale by Settings.grayScale.collectAsState()
        val invertedColors by Settings.invertedColors.collectAsState()
        val smartEnabled = readerTheme == READER_THEME_SMART
        val lastSmartColor = remember(pageLoader) { SmartColorHolder() }
        val currentPageIndex = if (isWebtoon) {
            (sync.slider.value - 1).coerceIn(0, (pageLoader.size - 1).coerceAtLeast(0))
        } else {
            pagerState.currentPage.coerceIn(0, (pageLoader.size - 1).coerceAtLeast(0))
        }
        val currentPageStatus = if (smartEnabled && pageLoader.size > 0) {
            pageLoader.pages.getOrNull(currentPageIndex)?.statusObserved
        } else {
            null
        }
        val targetBgColor = if (!smartEnabled) {
            fallbackBgColor
        } else {
            when (currentPageStatus) {
                is PageStatus.Ready -> {
                    val raw = currentPageStatus.image.backgroundColor
                    if (raw != null) {
                        Color(applyReaderColorFilters(raw, grayScale, invertedColors)).also {
                            lastSmartColor.color = it
                        }
                    } else {
                        fallbackBgColor
                    }
                }
                is PageStatus.Queued, is PageStatus.Loading -> lastSmartColor.color ?: fallbackBgColor
                is PageStatus.Error, is PageStatus.Blocked, null -> fallbackBgColor
            }
        }
        val bgColor by animateColorAsState(
            targetValue = targetBgColor,
            animationSpec = tween(durationMillis = 220),
            label = "ReaderBackground",
        )
        val isLightBg = targetBgColor.isLightColor()
        val isDarkTheme = isSystemInDarkTheme()
        LaunchedEffect(isDarkTheme, fullscreen, isLightBg) {
            snapshotFlow { appbarVisible }.collect {
                uiController.isSystemBarsVisible = it || !fullscreen
                uiController.statusBarDarkContentEnabled = if (it) !isDarkTheme else isLightBg
            }
        }
        var showNavigationOverlay by remember {
            val showOnStart = Settings.showNavigationOverlayNewUser.value || Settings.showNavigationOverlayOnStart.value
            Settings.showNavigationOverlayNewUser.value = false
            mutableStateOf(showOnStart)
        }
        val onSelectPage = { page: Page ->
            if (Settings.readerLongTapAction.value) {
                launch {
                    val blocked = page.status is PageStatus.Blocked
                    dialog { cont ->
                        fun dispose() = cont.resume(Unit)
                        val state = rememberBottomSheetState(SheetValue.Hidden)
                        ModalBottomSheet(
                            onDismissRequest = { dispose() },
                            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
                            sheetState = state,
                            contentWindowInsets = { WindowInsets() },
                        ) {
                            ReaderPageSheetMeta(
                                retry = { pageLoader.retryPage(page.index) },
                                retryOrigin = { pageLoader.retryPage(page.index, true) },
                                share = { launchIO { with(pageLoader) { shareImage(page, info) } } },
                                copy = { launchIO { with(pageLoader) { copy(page) } } },
                                save = { launchIO { with(pageLoader) { save(page) } } },
                                saveTo = { launchIO { with(pageLoader) { saveTo(page) } } },
                                showAds = { page.unblock() }.takeIf { blocked },
                                dismiss = { launch { state.hide().also { dispose() } } },
                            )
                        }
                    }
                }
            }
        }
        EhTheme(useDarkTheme = !isLightBg) {
            val insets = if (fullscreen) {
                if (cutoutShort) {
                    WindowInsets()
                } else {
                    WindowInsets.displayCutout
                }
            } else {
                WindowInsets.systemBars
            }
            if (animatedPlaybackActive) {
                AnimatedPlaybackViewer(
                    lazyListState = lazyListState,
                    pageLoader = pageLoader,
                    neighbors = animatedPlaybackNeighbors,
                    preloadCount = animatedPlaybackPreload,
                    speed = animatedPlaybackSpeed,
                    allAnimated = AnimatedPlaybackSession.allAnimated,
                    appbarVisible = appbarVisible,
                    onMenu = { appbarVisible = !appbarVisible },
                    modifier = Modifier.background(bgColor).fillMaxSize().windowInsetsPadding(insets),
                )
            } else {
                GalleryPager(
                    type = readingMode,
                    pagerState = pagerState,
                    lazyListState = lazyListState,
                    pageLoader = pageLoader,
                    showNavigationOverlay = showNavigationOverlay,
                    onNavigationModeChange = { showNavigationOverlay = true },
                    onSelectPage = onSelectPage,
                    onMenuRegionClick = { appbarVisible = !appbarVisible },
                    modifier = Modifier.background(bgColor).pointerInput(sync) {
                        awaitEachGesture {
                            waitForUpOrCancellation()
                            sync.reset()
                            showNavigationOverlay = false
                        }
                    }.fillMaxSize().windowInsetsPadding(insets),
                )
                AutoPageTurnEffect(
                    isWebtoon = isWebtoon,
                    appbarVisible = appbarVisible,
                    pagerState = pagerState,
                    lazyListState = lazyListState,
                    pageLoader = pageLoader,
                )
            }
            if (animatedLoadingDialogVisible && animatedPlaybackActive) {
                AnimatedLoadingDialog(
                    loaded = loadedAnimatedCount,
                    target = animatedLoadTarget,
                    readyToDismiss = AnimatedPlaybackSession.playbackStarted,
                    onClose = { animatedLoadingDialogVisible = false },
                )
            }
        }
        val brightness by Settings.customBrightness.collectAsState()
        val brightnessValue by Settings.customBrightnessValue.collectAsState()
        val colorOverlayEnabled by Settings.colorFilter.collectAsState()
        val colorOverlay by Settings.colorFilterValue.collectAsState()
        val colorOverlayMode by Settings.colorFilterMode.collectAsState {
            when (it) {
                0 -> BlendMode.SrcOver
                1 -> BlendMode.Multiply
                2 -> BlendMode.Screen
                3 -> BlendMode.Overlay
                4 -> BlendMode.Lighten
                5 -> BlendMode.Darken
                else -> unreachable()
            }
        }
        ReaderContentOverlay(
            brightness = { brightnessValue }.takeIf { brightness && brightnessValue < 0 },
            color = { colorOverlay }.takeIf { colorOverlayEnabled },
            colorBlendMode = colorOverlayMode,
        )
        if (brightness) {
            LaunchedEffect(Unit) {
                Settings.customBrightnessValue.valueFlow().sample(100.milliseconds)
                    .onCompletion { activity.setCustomBrightnessValue(0) }
                    .collect { activity.setCustomBrightnessValue(it) }
            }
        }
        val showPageNumber by Settings.showPageNumber.collectAsState()
        if (showPageNumber && !appbarVisible) {
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodySmall) {
                PageIndicatorText(
                    currentPage = sync.slider.value,
                    totalPages = pageLoader.size,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                )
            }
        }
        ReaderAppBars(
            visible = appbarVisible,
            title = pageLoader.title,
            isRtl = readingMode == ReadingModeType.RIGHT_TO_LEFT,
            showSeekBar = showSeekbar,
            slider = sync.slider,
            totalPages = pageLoader.size,
            pageLoader = pageLoader,
            onSliderValueChange = sync::sliderPreviewTo,
            onSliderValueChangeFinished = sync::sliderCommit,
            onClickSettings = {
                launch {
                    dialog { cont ->
                        fun dispose() = cont.resume(Unit)
                        var isColorFilter by remember { mutableStateOf(false) }
                        val scrim by animateColorAsState(
                            targetValue = if (isColorFilter) Color.Transparent else BottomSheetDefaults.ScrimColor,
                            label = "ScrimColor",
                        )
                        ModalBottomSheet(
                            onDismissRequest = { dispose() },
                            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
                            // Yeah, I know color state should not be read here, but we have to do it...
                            scrimColor = scrim,
                            dragHandle = null,
                            contentWindowInsets = { WindowInsets() },
                        ) {
                            SettingsPager(isWebtoon = isWebtoon, modifier = Modifier.fillMaxSize()) { page ->
                                isColorFilter = page == 2
                                appbarVisible = !isColorFilter
                            }
                        }
                    }
                }
            },
        )
    }
}

context(_: Context, _: DialogState, nav: DestinationsNavigator)
suspend inline fun <T> usePageLoader(args: ReaderScreenArgs, crossinline block: suspend (PageLoader) -> T): T {
    val passwdProvider: suspend ((String) -> Boolean) -> String = { invalidator ->
        awaitInputText(
            title = string(R.string.archive_need_passwd),
            hint = string(R.string.archive_passwd),
            onUserDismiss = { nav.popBackStack() },
        ) { text ->
            ensure(text.isNotBlank()) { string(R.string.passwd_cannot_be_empty) }
            ensure(invalidator(text)) { string(R.string.passwd_wrong) }
        }
    }
    return when (args) {
        is ReaderScreenArgs.Gallery -> {
            val info = args.info
            val page = args.page.takeUnless { it == -1 } ?: EhDB.getReadProgress(info.gid)
            val download = DownloadManager.getDownloadInfo(info.gid)
            // 只有明确的本地导入条目才用目录加载器。普通下载即使目录里已经有图片，
            // 也必须走网络加载器按详情页数补页；否则部分下载（9/531）会被 files.size
            // 截断成 9 页，在线阅读显示 9/9 且翻不过去。
            val localDir = download?.takeIf { it.isLocal }?.downloadDir?.takeIf { it.isDirectory }
            val localFiles = localDir?.let { LocalFolderPageLoader.imageFiles(it) }
            if (!localFiles.isNullOrEmpty()) {
                coroutineScope {
                    val loader = LocalFolderPageLoader(this, info, page, localFiles)
                    try {
                        block(loader)
                    } finally {
                        loader.close()
                    }
                }
            } else {
                val archive = download?.archiveFile
                if (archive != null) {
                    useArchivePageLoader(archive, info, page, info.hasAds, passwdProvider, block)
                } else {
                    useEhPageLoader(info, page, block)
                }
            }
        }
        is ReaderScreenArgs.Archive -> useArchivePageLoader(
            args.path.toPath(),
            passwdProvider = passwdProvider,
            block = block,
        )
    }
}

private class SmartColorHolder(var color: Color? = null)

private fun Color.isLightColor(): Boolean = luminance() > 0.5f

@Composable
private fun collectBackgroundColorAsState(): State<Color> {
    val grey = colorResource(com.hippo.ehviewer.R.color.reader_background_dark)
    val dark = isSystemInDarkTheme()
    return Settings.readerTheme.collectAsState { theme ->
        when (theme) {
            0 -> Color.White
            2 -> grey
            3, READER_THEME_SMART -> if (dark) grey else Color.White
            else -> Color.Black
        }
    }
}
