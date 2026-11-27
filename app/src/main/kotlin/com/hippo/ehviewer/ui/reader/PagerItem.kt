package com.hippo.ehviewer.ui.reader

import android.graphics.drawable.Animatable
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import coil3.BitmapImage
import coil3.DrawableImage
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.util.thenIf
import com.ehviewer.core.util.unreachable
import com.google.accompanist.drawablepainter.DrawablePainter
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.coil.AnimatedWebPDrawable
import com.hippo.ehviewer.coil.SpeedGifDrawable
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.PageLoader
import com.hippo.ehviewer.gallery.PageStatus
import com.hippo.ehviewer.gallery.progressObserved
import com.hippo.ehviewer.gallery.statusObserved
import com.hippo.ehviewer.image.Image
import com.hippo.ehviewer.util.AdsPlaceholderFile
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.drop

@Composable
fun PagerItem(
    page: Page,
    pageLoader: PageLoader,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    playbackActive: Boolean = false,
    playbackRunning: Boolean = true,
    playbackStay: Boolean = false,
    playbackKey: Int = 0,
    playbackSpeed: Float = 1f,
    onAnimationStart: (() -> Unit)? = null,
    onAnimationEnd: (() -> Unit)? = null,
) {
    LaunchedEffect(Unit) {
        pageLoader.request(page.index)
        // In case page loader restart
        page.statusFlow.drop(1).collect {
            if (page.statusFlow.value == PageStatus.Queued) {
                pageLoader.request(page.index)
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            pageLoader.cancelRequest(page.index)
        }
    }
    val defaultError = stringResource(id = R.string.decode_image_error)
    when (val state = page.statusObserved) {
        is PageStatus.Queued, is PageStatus.Loading -> {
            Box(
                modifier = modifier.fillMaxWidth().aspectRatio(DEFAULT_ASPECT),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = state is PageStatus.Loading,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "progressState",
                ) { determinate ->
                    val animatedProgress by animateFloatAsState(
                        targetValue = state.progressObserved,
                        animationSpec = WavyProgressIndicatorDefaults.ProgressAnimationSpec,
                        label = "progress",
                    )
                    if (determinate) {
                        CircularWavyProgressIndicator(progress = { animatedProgress })
                    } else {
                        CircularWavyProgressIndicator()
                    }
                }
            }
        }
        is PageStatus.Ready -> {
            val image = state.image
            var painter by remember { mutableStateOf<Painter?>(null) }
            LaunchedEffect(image) {
                if (image.pin()) {
                    painter = image.toPainter()
                    try {
                        awaitCancellation()
                    } finally {
                        if (image.unpin()) {
                            pageLoader.notifyPageWait(page.index)
                        }
                    }
                }
            }
            painter?.let { painter ->
                val drawable = (painter as? DrawablePainter)?.drawable
                val currentOnAnimationEnd by rememberUpdatedState(onAnimationEnd)
                val currentOnAnimationStart by rememberUpdatedState(onAnimationStart)
                // 支持 Android 9+ AnimatedImageDrawable 及 Android 8 MovieDrawable，并在 IO 线程异步解码 Movie。
                val isGifDrawable = drawable is Animatable && drawable !is AnimatedWebPDrawable && drawable !is SpeedGifDrawable
                val speedGif by produceState<SpeedGifDrawable?>(null, image, playbackActive, drawable) {
                    value = if (playbackActive && isGifDrawable) {
                        image.createGifMovie()?.let(::SpeedGifDrawable)
                    } else {
                        null
                    }
                }
                val displayDrawable = speedGif ?: drawable
                val displayPainter = remember(speedGif, painter) {
                    speedGif?.let { DrawablePainter(it) } ?: painter
                }
                // Apply a speed change live instead of restarting the animation sequence.
                LaunchedEffect(displayDrawable, playbackSpeed) {
                    when (displayDrawable) {
                        is AnimatedWebPDrawable -> displayDrawable.speed = playbackSpeed
                        is SpeedGifDrawable -> displayDrawable.speed = playbackSpeed
                        else -> Unit
                    }
                }
                LaunchedEffect(displayDrawable, playbackActive, playbackRunning, playbackStay, playbackKey) {
                    val animatable = displayDrawable as? Animatable ?: return@LaunchedEffect
                    if (!playbackActive) {
                        // 从动图模式切回普通阅读器时，恢复默认循环配置，防止缓存复用的动图只播一遍就卡死。
                        when {
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && displayDrawable is AnimatedImageDrawable -> {
                                displayDrawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                            }
                            displayDrawable is AnimatedWebPDrawable -> {
                                displayDrawable.playOnce = false
                                displayDrawable.loopForever = false
                                displayDrawable.onAnimationEnd = null
                            }
                        }
                        return@LaunchedEffect
                    }
                    if (!playbackRunning) {
                        animatable.stop()
                        return@LaunchedEffect
                    }
                    when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && displayDrawable is AnimatedImageDrawable -> {
                            displayDrawable.repeatCount =
                                if (playbackStay) AnimatedImageDrawable.REPEAT_INFINITE else 0
                            var ended = false
                            val callback = object : Animatable2.AnimationCallback() {
                                override fun onAnimationEnd(drawable: Drawable) {
                                    if (!ended) {
                                        ended = true
                                        currentOnAnimationEnd?.invoke()
                                    }
                                }
                            }
                            displayDrawable.registerAnimationCallback(callback)
                            displayDrawable.stop()
                            displayDrawable.start()
                            currentOnAnimationStart?.invoke()
                            try {
                                awaitCancellation()
                            } finally {
                                displayDrawable.unregisterAnimationCallback(callback)
                                displayDrawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                                displayDrawable.stop()
                            }
                        }
                        displayDrawable is AnimatedWebPDrawable -> {
                            displayDrawable.playOnce = !playbackStay
                            displayDrawable.loopForever = playbackStay
                            displayDrawable.onAnimationEnd = {
                                currentOnAnimationEnd?.invoke()
                            }
                            displayDrawable.restart()
                            currentOnAnimationStart?.invoke()
                            try {
                                awaitCancellation()
                            } finally {
                                displayDrawable.onAnimationEnd = null
                                displayDrawable.playOnce = false
                                displayDrawable.loopForever = false
                                displayDrawable.stop()
                            }
                        }
                        displayDrawable is SpeedGifDrawable -> {
                            displayDrawable.looping = playbackStay
                            displayDrawable.onAnimationEnd = {
                                currentOnAnimationEnd?.invoke()
                            }
                            displayDrawable.restart()
                            currentOnAnimationStart?.invoke()
                            try {
                                awaitCancellation()
                            } finally {
                                displayDrawable.onAnimationEnd = null
                                displayDrawable.stop()
                            }
                        }
                        else -> {
                            animatable.start()
                            currentOnAnimationStart?.invoke()
                            try {
                                awaitCancellation()
                            } finally {
                                animatable.stop()
                            }
                        }
                    }
                }
                val grayScale by Settings.grayScale.collectAsState()
                val invert by Settings.invertedColors.collectAsState()
                val enhanceModeRaw by Settings.imageEnhanceMode.collectAsState()
                val enhanceStrength by Settings.imageEnhanceStrength.collectAsState()
                val enhanceMode = ReaderImageEnhanceMode.fromPrefValue(enhanceModeRaw)
                var drawnSize by remember { mutableStateOf(IntSize.Zero) }
                val enhanceEffect = rememberImageEnhanceRenderEffect(
                    mode = enhanceMode,
                    strengthPercent = enhanceStrength,
                    drawnSize = drawnSize,
                    intrinsicSize = displayPainter.intrinsicSize,
                )
                Image(
                    // DrawablePainter <: RememberObserver
                    painter = remember(displayPainter) { displayPainter },
                    contentDescription = null,
                    modifier = Modifier.thenIf(displayDrawable is Animatable) {
                        onVisibilityChanged(minDurationMs = 33, minFractionVisible = 0.5f) {
                            displayDrawable!!.setVisible(it, false)
                        }
                    }.then(contentModifier)
                        .fillMaxSize()
                        .onSizeChanged { drawnSize = it }
                        .thenIf(enhanceEffect != null) {
                            graphicsLayer { renderEffect = enhanceEffect }
                        },
                    contentScale = contentScale,
                    colorFilter = when {
                        grayScale && invert -> grayScaleAndInvertFilter
                        grayScale -> grayScaleFilter
                        invert -> invertFilter
                        else -> null
                    },
                )
            } ?: Spacer(modifier = modifier.fillMaxWidth().aspectRatio(DEFAULT_ASPECT))
        }
        is PageStatus.Blocked -> {
            AdsPlaceholder(
                modifier = modifier.fillMaxSize(),
                contentScale = if (contentScale == ContentScale.Inside) ContentScale.Fit else contentScale,
            )
        }
        is PageStatus.Error -> {
            Box(modifier = modifier.fillMaxWidth().aspectRatio(DEFAULT_ASPECT)) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = state.message ?: defaultError,
                        modifier = Modifier.padding(8.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = { pageLoader.retryPage(page.index) },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.padding(8.dp),
                    ) {
                        Text(text = stringResource(id = R.string.action_retry))
                    }
                }
            }
        }
    }
}

private fun Image.toPainter() = when (val image = innerImage) {
    is BitmapImage -> BitmapPainter(image.bitmap, intrinsicSize.toSize())
    is DrawableImage -> DrawablePainter(image.drawable)
    else -> unreachable()
}

private const val DEFAULT_ASPECT = 1 / 1.4125f

private val invertMatrix = ColorMatrix(
    floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ),
)
private val grayScaleMatrix = ColorMatrix().apply { setToSaturation(0f) }
private val grayScaleAndInvertMatrix = ColorMatrix().also { mtx ->
    mtx.setToSaturation(0f)
    mtx *= invertMatrix
}

private val grayScaleFilter = ColorFilter.colorMatrix(grayScaleMatrix)
private val invertFilter = ColorFilter.colorMatrix(invertMatrix)
private val grayScaleAndInvertFilter = ColorFilter.colorMatrix(grayScaleAndInvertMatrix)

@Composable
fun AdsPlaceholder(
    modifier: Modifier = Modifier,
    contentScale: ContentScale,
) = SubcomposeAsyncImage(
    model = AdsPlaceholderFile,
    contentDescription = null,
    modifier = modifier,
    contentScale = contentScale,
) {
    val placeholderState by painter.state.collectAsState()
    if (placeholderState is AsyncImagePainter.State.Success) {
        SubcomposeAsyncImageContent()
    } else {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(DEFAULT_ASPECT),
            contentAlignment = Alignment.Center,
        ) {
            if (placeholderState is AsyncImagePainter.State.Error) {
                Text(text = stringResource(id = R.string.blocked_image))
            }
        }
    }
}
