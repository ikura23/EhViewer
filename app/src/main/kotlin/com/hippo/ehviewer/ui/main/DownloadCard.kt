package com.hippo.ehviewer.ui.main

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.files.isFile
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.CrystalCard
import com.ehviewer.core.ui.component.GalleryListCardRating
import com.ehviewer.core.ui.util.TransitionsVisibilityScope
import com.ehviewer.core.ui.util.listThumbGenerator
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.download.archiveThumbPath
import com.hippo.ehviewer.download.downloadDir
import com.hippo.ehviewer.download.isLocal
import com.hippo.ehviewer.gallery.LocalFolderPageLoader
import com.hippo.ehviewer.util.AppConfig
import com.hippo.ehviewer.util.FileUtils
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) = CrystalCard(modifier = modifier, onClick = onClick, onLongClick = onLongClick, interactionSource = interactionSource) {
    Row {
        val thumb = remember {
            movableContentOf<DownloadInfo, Any> { item, refreshKey ->
                if (item.isLocal || item.thumbKey.isNullOrBlank() || '/' !in item.thumbKey!!) {
                    LocalDownloadThumb(
                        info = item,
                        thumbnailRefreshKey = refreshKey,
                        modifier = Modifier.aspectRatio(DEFAULT_RATIO).fillMaxSize(),
                    )
                } else {
                    with(listThumbGenerator) {
                        if (item.thumbKey != null) {
                            EhAsyncCropThumb(
                                key = item,
                                modifier = Modifier.aspectRatio(DEFAULT_RATIO).fillMaxSize(),
                            )
                        } else {
                            Box(
                                modifier = Modifier.aspectRatio(DEFAULT_RATIO).fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                        }
                    }
                }
            }
        }
        if (selectMode) {
            Card {
                thumb(info, thumbnailRefreshKey)
            }
        } else {
            Card(onClick = onThumbClick) {
                thumb(info, thumbnailRefreshKey)
            }
        }
        val stateFailed = stringResource(R.string.download_state_failed)
        val stateFailed2 = stringResource(R.string.download_state_failed_2, info.legacy)
        val downloadState by DownloadManager.collectDownloadState(info.gid)
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
                        if (showProgress) {
                            val readProgress by remember { EhDB.getReadProgressFlow(info.gid) }.collectAsState(0)
                            if (readProgress > 0) "${readProgress + 1}/${info.pages}P" else "${info.pages}P"
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
                        Row {
                            Text(text = "$finished/$total")
                            Spacer(modifier = Modifier.weight(1f))
                            Text(text = FileUtils.humanReadableByteCount(speed.coerceAtLeast(0)) + "/S")
                        }
                        LinearWavyProgressIndicator(
                            progress = { finished.toFloat() / total.toFloat() },
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
                    Text(
                        text = categoryText,
                        modifier = Modifier.clip(ShapeDefaults.Small).background(categoryColor).padding(vertical = 2.dp, horizontal = 8.dp),
                        color = EhUtils.getCategoryTextColor(categoryColor),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                val running = downloadState == DownloadInfo.STATE_WAIT || downloadState == DownloadInfo.STATE_DOWNLOAD
                val icon = when (downloadState) {
                    DownloadInfo.STATE_WAIT, DownloadInfo.STATE_DOWNLOAD -> Icons.Default.Pause
                    DownloadInfo.STATE_FINISH -> Icons.Default.DownloadDone
                    else -> Icons.Default.PlayArrow
                }
                if (selectMode) {
                    Box(modifier = Modifier.offset(4.dp).minimumInteractiveComponentSize()) {
                        Icon(imageVector = icon, contentDescription = null)
                    }
                } else {
                    IconButton(onClick = if (running) onStop else onStart, shapes = IconButtonDefaults.shapes(), modifier = Modifier.offset(4.dp)) {
                        Icon(imageVector = icon, contentDescription = null)
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
    modifier: Modifier = Modifier,
) {
    val thumbState by produceState<LocalThumbResult?>(null, info.gid, info.dirname, thumbnailRefreshKey) {
        value = withContext(Dispatchers.IO) {
            resolveLocalThumb(info)
        }
    }
    val resolved = thumbState
    if (resolved != null) {
        val context = LocalContext.current
        val request = remember(resolved, context) {
            ImageRequest.Builder(context)
                .data(resolved.file)
                .memoryCacheKey(resolved.cacheKey)
                .diskCacheKey(resolved.cacheKey)
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

private data class LocalThumbResult(
    val file: File,
    val cacheKey: String,
)

private fun resolveLocalThumb(info: DownloadInfo): LocalThumbResult? = runCatching {
    val dir = info.downloadDir ?: return@runCatching null
    val resolvedFile = if (dir.isFile) {
        val cachePrefix = "${info.gid}-${dir.toString().hashCode().toUInt().toString(16)}"
        val cached = AppConfig.localThumbDir.toFile().listFiles()
            ?.firstOrNull {
                it.nameWithoutExtension == cachePrefix && it.isFile && it.length() > 0
            }
        if (cached != null && isUsableThumb(cached)) {
            cached
        } else {
            cached?.delete()
            val archiveThumb = File(archiveThumbPath(info.gid).toString())
            if (archiveThumb.isFile && isUsableThumb(archiveThumb)) {
                archiveThumb
            } else {
                archiveThumb.delete()
                createArchiveThumb(File(dir.toString()), info.gid, cachePrefix)
            }
        }
    } else {
        val cached = File(AppConfig.localThumbDir.toString(), "${info.gid}.jpg")
        if (cached.isFile && isUsableThumb(cached)) {
            cached
        } else {
            LocalFolderPageLoader.imageFiles(dir).firstOrNull()?.let { File(it.toString()) }
        }
    } ?: return@runCatching null
    val key = "local-thumb:${resolvedFile.absolutePath}:${resolvedFile.lastModified()}:${resolvedFile.length()}"
    LocalThumbResult(resolvedFile, key)
}.getOrNull()

private fun isUsableThumb(file: File): Boolean {
    if (!file.isFile || file.length() == 0L) return false
    if (file.extension.lowercase() in setOf("gif", "webp")) return true
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, opts)
    return opts.outWidth > 0 && opts.outHeight > 0
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
                val bytes = runCatching { zip.getInputStream(entry).use { it.readBytes() } }.getOrNull() ?: continue
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue
                var sampleSize = 1
                while (bounds.outWidth / (sampleSize * 2) >= 320) {
                    sampleSize *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: continue
                if (bitmap.width <= 0 || bitmap.height <= 0) {
                    bitmap.recycle()
                    continue
                }
                val width = 320
                val height = (bitmap.height.toFloat() * width / bitmap.width).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                val saved = runCatching {
                    output.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
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
