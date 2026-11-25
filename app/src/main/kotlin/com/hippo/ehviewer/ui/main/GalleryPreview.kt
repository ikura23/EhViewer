package com.hippo.ehviewer.ui.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil3.BitmapImage
import coil3.compose.AsyncImagePainter.State
import coil3.compose.rememberAsyncImagePainter
import com.ehviewer.core.model.GalleryPreview
import com.ehviewer.core.model.V2GalleryPreview
import com.ehviewer.core.ui.component.CrystalCard
import com.hippo.ehviewer.download.isAnimatedImageFile
import com.hippo.ehviewer.ktbuilder.imageRequest
import com.hippo.ehviewer.ui.tools.shouldCrop
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
@NonRestartableComposable
fun requestOf(model: GalleryPreview) = with(LocalContext.current) {
    remember(model) { imageRequest(model) }
}

@Composable
fun EhPreviewCard(
    model: GalleryPreview,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    localPageFile: File? = null,
) {
    val animatedLocalFile by produceState<File?>(null, localPageFile) {
        value = withContext(Dispatchers.IO) {
            localPageFile?.takeIf {
                it.isFile &&
                    it.length() > 0L &&
                    it.length() <= MAX_ANIMATED_PREVIEW_BYTES &&
                    isAnimatedImageFile(it)
            }
        }
    }
    var contentScale by remember(model) { mutableStateOf(ContentScale.Fit) }
    val request = requestOf(model)
    val painter = rememberAsyncImagePainter(
        model = request,
        transform = {
            if (it is State.Success && model is V2GalleryPreview) {
                with(model) {
                    it.copy(
                        painter = BitmapPainter(
                            (it.result.image as BitmapImage).bitmap.asImageBitmap(),
                            IntOffset(offsetX, 0),
                            IntSize(clipWidth - 1, clipHeight - 1),
                        ),
                    )
                }
            } else {
                it
            }
        },
        onState = {
            if (it is State.Success) {
                if (model is V2GalleryPreview) {
                    if (model.shouldCrop) {
                        contentScale = ContentScale.Crop
                    }
                } else {
                    if (it.result.image.shouldCrop) {
                        contentScale = ContentScale.Crop
                    }
                }
            }
        },
    )
    CrystalCard(
        onClick = onClick,
        onLongClick = {
            if (painter.state.value is State.Error) {
                painter.restart()
            }
        },
        modifier = modifier,
    ) {
        val localAnim = animatedLocalFile
        if (localAnim != null) {
            AnimatedPreviewImage(
                data = localAnim,
                cacheKey = "preview:${localAnim.absolutePath}:${localAnim.lastModified()}:${localAnim.length()}",
                sceneTag = "detail-preview-${model.position}",
                modifier = Modifier.fillMaxSize(),
                speed = PREVIEW_ANIMATED_DEFAULT_SPEED,
                contentScale = ContentScale.Crop,
                onError = {
                    Image(
                        painter = painter,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = contentScale,
                    )
                },
            )
        } else {
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        }
    }
}

@Composable
@NonRestartableComposable
fun EhPreviewItem(
    galleryPreview: GalleryPreview?,
    position: Int,
    onClick: () -> Unit,
    localPageFile: File? = null,
) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(contentAlignment = Alignment.Center) {
        if (galleryPreview != null) {
            EhPreviewCard(
                model = galleryPreview,
                onClick = onClick,
                modifier = Modifier.aspectRatio(DEFAULT_RATIO),
                localPageFile = localPageFile,
            )
        } else {
            CrystalCard(
                onClick = onClick,
                modifier = Modifier.aspectRatio(DEFAULT_RATIO),
            ) {}
        }
    }
    Text(text = "${position + 1}")
}
