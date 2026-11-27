package com.hippo.ehviewer.ui.main

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.files.isFile
import com.ehviewer.core.files.list
import com.ehviewer.core.files.toUri
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.CrystalCard
import com.ehviewer.core.ui.component.GalleryListCardRating
import com.ehviewer.core.ui.component.RollingNumber
import com.ehviewer.core.ui.component.RollingNumberPlaceholder
import com.ehviewer.core.ui.util.HapticFeedbackType
import com.ehviewer.core.ui.util.TransitionsVisibilityScope
import com.ehviewer.core.ui.util.listThumbGenerator
import com.ehviewer.core.ui.util.rememberHapticFeedback
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.LocalGalleryScanner
import com.hippo.ehviewer.download.archiveThumbPath
import com.hippo.ehviewer.download.downloadDir
import com.hippo.ehviewer.download.isAnimatedImageFile
import com.hippo.ehviewer.download.isAnimatedImageHeader
import com.hippo.ehviewer.download.isLocal
import com.hippo.ehviewer.download.resolveDownloadLocationPath
import com.hippo.ehviewer.gallery.LocalFolderPageLoader
import com.hippo.ehviewer.util.AppConfig
import com.hippo.ehviewer.util.FileUtils
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath

@Composable
context(_: SharedTransitionScope, _: TransitionsVisibilityScope)
fun DownloadCard(
    onClick: () -> Unit,
    onThumbClick: () -> Unit,
    onLongClick: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    info: DownloadInfo,
    selectMode: Boolean,
    showProgress: Boolean,
    thumbnailRefreshKey: Any = Unit,
    dragHandleModifier: Modifier? = null,
    isDragging: Boolean = false,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val dragElevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "DragElevation")
    val dragScale by animateFloatAsState(
        targetValue = if (isDragging) 1.02f else 1f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        label = "DragScale",
    )
    CrystalCard(
        modifier = modifier
            .graphicsLayer {
                scaleX = dragScale
                scaleY = dragScale
            }
            .shadow(dragElevation, CardDefaults.shape),
        onClick = onClick,
        onLongClick = onLongClick,
        interactionSource = interactionSource,
    ) {
    val downloadState by DownloadManager.collectDownloadState(info.gid)
    val readProgress by if (showProgress && downloadState == DownloadInfo.STATE_FINISH) {
        remember(info.gid) { EhDB.getReadProgressFlow(info.gid) }.collectAsState(0)
    } else {
        remember { mutableIntStateOf(0) }
    }
    Row {
        val thumb = remember {
            movableContentOf<DownloadInfo, Any> { item, refreshKey ->
                val preferLocal = item.isLocal ||
                    item.thumbKey.isNullOrBlank() ||
                    '/' !in item.thumbKey!! ||
                    item.state == DownloadInfo.STATE_FINISH
                if (preferLocal) {
                    LocalDownloadThumb(
                        info = item,
                        thumbnailRefreshKey = refreshKey,
                        animatedOnly = false,
                        sceneTag = "download-list",
                        speed = PREVIEW_ANIMATED_DEFAULT_SPEED,
                        modifier = Modifier.aspectRatio(DEFAULT_RATIO).fillMaxSize(),
                        fallbackContent = {
                            if (!item.thumbKey.isNullOrBlank() && '/' in item.thumbKey!!) {
                                with(listThumbGenerator) {
                                    EhAsyncCropThumb(
                                        key = item,
                                        modifier = Modifier.aspectRatio(DEFAULT_RATIO).fillMaxSize(),
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier.aspectRatio(DEFAULT_RATIO).fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                )
                            }
                        },
                    )
                } else {
                    with(listThumbGenerator) {
                        EhAsyncCropThumb(
                            key = item,
                            modifier = Modifier.aspectRatio(DEFAULT_RATIO).fillMaxSize(),
                        )
                    }
                }
            }
        }
        val progressRatio = if (showProgress && downloadState == DownloadInfo.STATE_FINISH && info.pages > 0 && readProgress > 0) {
            ((readProgress + 1).toFloat() / info.pages).coerceIn(0f, 1f)
        } else {
            0f
        }
        val thumbWithProgress: @Composable () -> Unit = {
            Box(modifier = Modifier.aspectRatio(DEFAULT_RATIO).fillMaxSize()) {
                thumb(info, thumbnailRefreshKey)
                if (progressRatio > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.Black.copy(alpha = 0.28f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressRatio)
                                .height(3.dp)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
        }
        if (selectMode) {
            Card {
                thumbWithProgress()
            }
        } else {
            Card(onClick = onThumbClick) {
                thumbWithProgress()
            }
        }
        val stateFailed = stringResource(R.string.download_state_failed)
        val stateFailed2 = stringResource(R.string.download_state_failed_2, info.legacy)
        Column(modifier = Modifier.padding(start = 8.dp, top = 2.dp, end = 4.dp)) {
            Text(
                text = EhUtils.getSuitableTitle(info),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (downloadState != DownloadInfo.STATE_DOWNLOAD) {
                val stateText = when (downloadState) {
                    DownloadInfo.STATE_NONE -> stringResource(R.string.download_state_none)
                    DownloadInfo.STATE_WAIT -> stringResource(R.string.download_state_wait)
                    DownloadInfo.STATE_FAILED -> if (info.legacy <= 0) stateFailed else stateFailed2
                    DownloadInfo.STATE_FINISH -> {
                        if (showProgress && readProgress > 0) {
                            "${readProgress + 1}/${info.pages}P"
                        } else {
                            "${info.pages}P"
                        }
                    }
                    else -> null // The item has been removed and this will be disposed soon
                }
                ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                    Row {
                        Text(
                            text = info.uploader.orEmpty(),
                            modifier = Modifier.alignByBaseline().alpha(if (info.disowned) 0.5f else 1f),
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = stateText.orEmpty(),
                            modifier = Modifier.alignByBaseline(),
                        )
                    }
                }
            } else {
                var total by remember { mutableIntStateOf(info.total) }
                var finished by remember { mutableIntStateOf(info.finished) }
                var speed by remember { mutableLongStateOf(info.speed) }
                DownloadManager.updatedDownloadInfo(info) {
                    total = this.total
                    finished = this.finished
                    speed = this.speed
                }
                ProvideTextStyle(MaterialTheme.typography.labelMedium) {
                    if (total <= 0 || finished < 0) {
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        val animatedProgress by animateFloatAsState(
                            targetValue = (finished.toFloat() / total.toFloat()).coerceIn(0f, 1f),
                            animationSpec = tween(durationMillis = 200),
                            label = "DownloadProgress",
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RollingNumber(number = finished)
                                Text(text = "/")
                                RollingNumberPlaceholder(number = total)
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            Text(text = FileUtils.humanReadableByteCount(speed.coerceAtLeast(0)) + "/S")
                        }
                        LinearWavyProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Row {
                Column {
                    // Place the rating near the uploader text as there's more visual space
                    GalleryListCardRating(rating = info.rating, modifier = Modifier.padding(top = 1.dp, bottom = 3.dp))
                    val categoryColor = EhUtils.getCategoryColor(info.category)
                    val categoryText = EhUtils.getCategory(info.category).uppercase()
                    // 仅外部导入并打上“本地”分组的条目显示“本地”徽标；下载目录内条目不展示该徽标
                    val showLocalBadge = info.label == "本地"
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = categoryText,
                            modifier = Modifier.clip(ShapeDefaults.Small).background(categoryColor).padding(vertical = 2.dp, horizontal = 8.dp),
                            color = EhUtils.getCategoryTextColor(categoryColor),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        if (showLocalBadge) {
                            Text(
                                text = stringResource(R.string.download_local_badge),
                                modifier = Modifier.clip(ShapeDefaults.Small).background(MaterialTheme.colorScheme.secondaryContainer).padding(vertical = 2.dp, horizontal = 6.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                val running = downloadState == DownloadInfo.STATE_WAIT || downloadState == DownloadInfo.STATE_DOWNLOAD
                val actionDesc = when {
                    downloadState == DownloadInfo.STATE_FINISH -> stringResource(R.string.download_state_finish)
                    running -> stringResource(R.string.action_cancel)
                    else -> stringResource(R.string.download)
                }
                val dragHandleDesc = stringResource(R.string.manual_order)
                val hapticFeedback = rememberHapticFeedback()
                var previousState by remember(info.gid) { mutableIntStateOf(downloadState) }
                val doneScale = remember(info.gid) { Animatable(1f) }
                LaunchedEffect(info.gid, downloadState) {
                    val justCompleted = (previousState == DownloadInfo.STATE_WAIT || previousState == DownloadInfo.STATE_DOWNLOAD) &&
                        downloadState == DownloadInfo.STATE_FINISH
                    previousState = downloadState
                    if (justCompleted) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.END)
                        doneScale.snapTo(0.85f)
                        doneScale.animateTo(1.22f, tween(durationMillis = 110))
                        doneScale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium))
                    }
                }
                val animatedStateIcon: @Composable (Int, String) -> Unit = { state, desc ->
                    AnimatedContent(
                        targetState = state,
                        transitionSpec = {
                            (fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.78f)) togetherWith
                                (fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.78f))
                        },
                        label = "DownloadStateIcon",
                    ) { targetState ->
                        val icon = when (targetState) {
                            DownloadInfo.STATE_WAIT, DownloadInfo.STATE_DOWNLOAD -> Icons.Default.Pause
                            DownloadInfo.STATE_FINISH -> Icons.Default.DownloadDone
                            else -> Icons.Default.PlayArrow
                        }
                        val iconModifier = if (targetState == DownloadInfo.STATE_FINISH) {
                            Modifier.graphicsLayer {
                                scaleX = doneScale.value
                                scaleY = doneScale.value
                            }
                        } else {
                            Modifier
                        }
                        Icon(imageVector = icon, contentDescription = desc, modifier = iconModifier)
                    }
                }
                if (selectMode) {
                    Box(modifier = Modifier.offset(4.dp).minimumInteractiveComponentSize()) {
                        animatedStateIcon(downloadState, actionDesc)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = if (running) onStop else onStart, shapes = IconButtonDefaults.shapes(), modifier = Modifier.offset(4.dp)) {
                            animatedStateIcon(downloadState, actionDesc)
                        }
                        if (dragHandleModifier != null) {
                            Box(
                                modifier = dragHandleModifier.minimumInteractiveComponentSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(imageVector = Icons.Default.Reorder, contentDescription = dragHandleDesc)
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
fun LocalDownloadThumb(
    info: DownloadInfo,
    thumbnailRefreshKey: Any = Unit,
    animatedOnly: Boolean = false,
    sceneTag: String = "download-list",
    speed: Float = PREVIEW_ANIMATED_DEFAULT_SPEED,
    contentScale: ContentScale = ContentScale.Crop,
    modifier: Modifier = Modifier,
    fallbackContent: (@Composable () -> Unit)? = null,
) {
    val cacheKey = LocalThumbCacheKey(info.gid, info.dirname, animatedOnly)
    val initialThumb = remember(cacheKey) { localThumbMemoryCache.get(cacheKey) }
    val hadInitialCache = remember(cacheKey) { initialThumb != null }
    val thumbState by produceState(initialThumb, info.gid, info.dirname, animatedOnly, thumbnailRefreshKey) {
        val fresh = withContext(Dispatchers.IO) {
            resolveLocalThumb(info, animatedOnly)
        }
        if (fresh != null) {
            localThumbMemoryCache.put(cacheKey, fresh)
        } else {
            localThumbMemoryCache.remove(cacheKey)
        }
        if (value != fresh) {
            value = fresh
        }
    }
    val resolved = thumbState
    val fadeAlpha by animateFloatAsState(
        targetValue = if (resolved != null) 1f else 0f,
        animationSpec = tween(durationMillis = if (hadInitialCache) 0 else 150),
        label = "LocalThumbFade",
    )
    if (resolved != null) {
        Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
            AnimatedPreviewImage(
                data = resolved.data,
                cacheKey = resolved.cacheKey,
                sceneTag = sceneTag,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fadeAlpha },
                speed = speed,
                contentScale = contentScale,
                onError = fallbackContent,
            )
        }
    } else if (fallbackContent != null) {
        fallbackContent()
    } else {
        Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

internal data class LocalThumbCacheKey(
    val gid: Long,
    val dirname: String?,
    val animatedOnly: Boolean,
)

internal data class LocalThumbResult(
    val data: Any,
    val cacheKey: String,
)

internal class LocalThumbMemoryCache(private val maxEntries: Int = 256) {
    private data class Entry(
        val result: LocalThumbResult,
        val lastModified: Long,
        val fileLength: Long,
    )

    private val lock = Any()
    private val map = object : LinkedHashMap<LocalThumbCacheKey, Entry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LocalThumbCacheKey, Entry>?): Boolean =
            size > maxEntries
    }

    fun get(key: LocalThumbCacheKey): LocalThumbResult? = synchronized(lock) {
        val entry = map[key] ?: return null
        val file = entry.result.data as? File
        if (file != null && (!file.isFile || file.lastModified() != entry.lastModified || file.length() != entry.fileLength)) {
            map.remove(key)
            return null
        }
        entry.result
    }

    fun put(key: LocalThumbCacheKey, result: LocalThumbResult) {
        val file = result.data as? File
        val entry = Entry(
            result = result,
            lastModified = file?.lastModified() ?: -1L,
            fileLength = file?.length() ?: -1L,
        )
        synchronized(lock) {
            map[key] = entry
        }
    }

    fun remove(key: LocalThumbCacheKey) {
        synchronized(lock) {
            map.remove(key)
        }
    }

    fun size(): Int = synchronized(lock) { map.size }
}

internal val localThumbMemoryCache = LocalThumbMemoryCache()

private const val MAX_ANIMATED_THUMB_BYTES = MAX_ANIMATED_PREVIEW_BYTES

/**
 * 按固定文件名 O(1) 探测压缩包缓存封面，避免每次卡片绑定执行 `listFiles()` 全目录遍历。
 */
internal fun findCachedArchiveThumb(
    thumbDir: File,
    cachePrefix: String,
    animatedOnly: Boolean,
    bitmapValidator: (File) -> Boolean = ::isUsableThumb,
): File? {
    for (ext in listOf("webp", "gif")) {
        val animCandidate = File(thumbDir, "$cachePrefix.$ext")
        if (animCandidate.isFile && animCandidate.length() > 0L && isAnimatedImageFile(animCandidate)) {
            return animCandidate
        }
    }
    if (animatedOnly) return null

    for (ext in listOf("jpg", "jpeg", "png", "webp")) {
        val candidate = File(thumbDir, "$cachePrefix.$ext")
        if (!candidate.isFile) continue
        if (candidate.length() > 0L && bitmapValidator(candidate)) {
            return candidate
        }
        candidate.delete()
    }
    return null
}

private fun resolveLocalThumb(info: DownloadInfo, animatedOnly: Boolean): LocalThumbResult? = runCatching {
    var dir = info.downloadDir ?: return@runCatching null
    val rawDir = dir.toString()
    if (rawDir.startsWith("content:") && LocalGalleryScanner.hasAllFilesAccess()) {
        val physical = resolveDownloadLocationPath(rawDir, Environment.getExternalStorageDirectory().absolutePath)
        if (physical != null && File(physical).exists()) {
            dir = physical.toPath()
        }
    }

    val isPhysical = dir.toString().startsWith("/")
    if (!isPhysical) {
        // SAF content:// 路径绝不能包装成 File；animatedOnly 模式下直接回退远程缩略图防止破图
        if (animatedOnly) return@runCatching null
        val cachedJpg = File(AppConfig.localThumbDir.toString(), "${info.gid}.jpg")
        if (cachedJpg.isFile && isUsableThumb(cachedJpg, minWidth = 500)) {
            val key = "local-thumb:${info.gid}:${cachedJpg.absolutePath}:${cachedJpg.lastModified()}:${cachedJpg.length()}"
            return@runCatching LocalThumbResult(cachedJpg, key)
        }
        if (cachedJpg.isFile) cachedJpg.delete()
        val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif", "avif")
        val firstChild = runCatching {
            dir.list().filter { entry ->
                !entry.name.equals("thumb.webp", ignoreCase = true) &&
                    entry.name.substringAfterLast('.', "").lowercase() in imageExts
            }.minWithOrNull { a, b ->
                LocalFolderPageLoader.NATURAL_NAME_COMPARATOR.compare(a.name, b.name)
            }
        }.getOrNull() ?: return@runCatching null
        val uri = firstChild.toUri()
        return@runCatching LocalThumbResult(uri, "local-thumb:${info.gid}:$uri")
    }

    val physicalFile = File(dir.toString())
    val candidateFile: File? = if (physicalFile.isFile) {
        val cachePrefix = "v2-${info.gid}-${dir.toString().hashCode().toUInt().toString(16)}"
        val cached = findCachedArchiveThumb(AppConfig.localThumbDir.toFile(), cachePrefix, animatedOnly)
        if (cached != null) {
            cached
        } else if (animatedOnly) {
            null
        } else {
            val archiveThumb = File(archiveThumbPath(info.gid).toString())
            if (archiveThumb.isFile && isUsableThumb(archiveThumb, minWidth = 500)) {
                archiveThumb
            } else {
                archiveThumb.delete()
                createArchiveThumb(physicalFile, info.gid, cachePrefix)
            }
        }
    } else if (physicalFile.isDirectory) {
        // 物理目录直接优先使用首张高清本地原图，清理旧版 320px 低清缓存，让 Coil 按屏幕尺寸高清解码。
        val firstFile = if (animatedOnly) {
            findFirstAnimatedImageInDir(physicalFile)
        } else {
            findFirstImageFileInDir(physicalFile)
        }
        val legacyJpg = File(AppConfig.localThumbDir.toString(), "${info.gid}.jpg")
        if (legacyJpg.isFile) legacyJpg.delete()
        if (firstFile != null && firstFile.isFile) {
            if (animatedOnly && (firstFile.length() > MAX_ANIMATED_THUMB_BYTES || !isAnimatedImageFile(firstFile))) {
                null
            } else {
                firstFile
            }
        } else {
            null
        }
    } else {
        null
    }
    val resolvedFile = candidateFile ?: return@runCatching null
    val key = "local-thumb:${info.gid}:${resolvedFile.absolutePath}:${resolvedFile.lastModified()}:${resolvedFile.length()}"
    LocalThumbResult(resolvedFile, key)
}.getOrNull()

private fun isUsableThumb(file: File, minWidth: Int = 1): Boolean {
    if (!file.isFile || file.length() == 0L) return false
    if (file.extension.lowercase() in setOf("gif", "webp")) return true
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, opts)
    return opts.outWidth >= minWidth && opts.outHeight > 0
}

private fun createArchiveThumb(archive: File, gid: Long, cachePrefix: String): File? {
    if (!archive.isFile) return null
    val output = File(AppConfig.localThumbDir.toString(), "$cachePrefix.jpg")
    val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif", "avif")
    return runCatching {
        ZipFile(archive).use { zip ->
            val sortedEntries = zip.entries().toList()
                .filter { entry ->
                    if (entry.isDirectory) return@filter false
                    val normalized = entry.name.replace('\\', '/')
                    if (normalized.startsWith("__MACOSX/") || normalized.contains("/__MACOSX/")) return@filter false
                    val fileName = normalized.substringAfterLast('/')
                    !fileName.startsWith("._") &&
                        !fileName.equals("thumb.webp", ignoreCase = true) &&
                        fileName.substringAfterLast('.', "").lowercase() in imageExts
                }
                .sortedWith { a, b -> LocalFolderPageLoader.NATURAL_NAME_COMPARATOR.compare(a.name, b.name) }
            for (entry in sortedEntries) {
                if (entry.size > 32L * 1024 * 1024) continue
                val ext = entry.name.substringAfterLast('.', "").lowercase()
                val bytes = runCatching { zip.getInputStream(entry).use { it.readBytes() } }.getOrNull() ?: continue
                if (ext == "gif" || isAnimatedImageHeader(bytes)) {
                    val outExt = if (ext == "webp" || ext == "gif") ext else "gif"
                    val animOut = File(AppConfig.localThumbDir.toString(), "$cachePrefix.$outExt")
                    if (runCatching { animOut.writeBytes(bytes) }.isSuccess && animOut.length() > 0L) {
                        return@use animOut
                    }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue
                val targetWidth = 640
                var sampleSize = 1
                while (bounds.outWidth / (sampleSize * 2) >= targetWidth) {
                    sampleSize *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: continue
                if (bitmap.width <= 0 || bitmap.height <= 0) {
                    bitmap.recycle()
                    continue
                }
                val scaled = if (bitmap.width > targetWidth) {
                    val height = (bitmap.height.toFloat() * targetWidth / bitmap.width).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(bitmap, targetWidth, height, true)
                } else {
                    bitmap
                }
                val saved = runCatching {
                    output.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                }.getOrDefault(false)
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
                if (saved && output.length() > 0L) {
                    runCatching {
                        File(archiveThumbPath(gid).toString()).outputStream().use {
                            output.inputStream().use { input -> input.copyTo(it) }
                        }
                    }
                    return@use output
                }
            }
            null
        }
    }.getOrNull()
}
