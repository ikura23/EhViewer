package eu.kanade.tachiyomi.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ehviewer.core.ui.component.IntSliderState
import com.ehviewer.core.ui.component.RollingNumber
import com.ehviewer.core.ui.component.RollingNumberPlaceholder
import com.ehviewer.core.ui.component.Slider
import com.ehviewer.core.ui.component.defaultMaxTickCount
import com.ehviewer.core.ui.util.HapticFeedbackType
import com.ehviewer.core.ui.util.LocalWindowSizeClass
import com.ehviewer.core.ui.util.isExpanded
import com.ehviewer.core.ui.util.rememberHapticFeedback
import com.hippo.ehviewer.ui.reader.calculatePreviewBubbleOffsetFraction
import kotlin.math.roundToInt

@Composable
fun ChapterNavigator(
    isRtl: Boolean,
    slider: IntSliderState,
    totalPages: Int,
    onSliderValueChange: (Int) -> Unit,
    containerColor: Color,
    onSliderValueChangeFinished: () -> Unit = {},
    previewContent: (@Composable (pageOneBased: Int) -> Unit)? = null,
) {
    val windowSizeClass = LocalWindowSizeClass.current
    val horizontalPadding = if (windowSizeClass.isExpanded) 24.dp else 16.dp
    val interactionSource = remember { MutableInteractionSource() }
    val sliderDragged by interactionSource.collectIsDraggedAsState()
    var dragStarted by remember { mutableStateOf(false) }
    LaunchedEffect(sliderDragged) {
        if (sliderDragged) {
            dragStarted = true
        } else if (dragStarted) {
            dragStarted = false
            onSliderValueChangeFinished()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (previewContent != null) {
            AnimatedVisibility(
                visible = sliderDragged,
                enter = fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.92f),
                exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.92f),
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    var cardWidthPx by remember { mutableIntStateOf(0) }
                    val fraction = calculatePreviewBubbleOffsetFraction(
                        pageOneBased = slider.value,
                        totalPages = totalPages,
                        isRtl = isRtl,
                    )
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val availableWidth = (constraints.maxWidth - cardWidthPx).coerceAtLeast(0)
                        val offsetX = (availableWidth * fraction).roundToInt().coerceIn(0, availableWidth)
                        Box(
                            modifier = Modifier
                                .offset { IntOffset(offsetX, 0) }
                                .onSizeChanged { cardWidthPx = it.width },
                        ) {
                            previewContent(slider.value)
                        }
                    }
                }
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Row(
                modifier = Modifier.fillMaxWidth().clip(CircleShape).background(containerColor).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RollingNumber(slider.value, length = "$totalPages".length)
                val maxTickCount = defaultMaxTickCount()
                if (slider.state.steps < maxTickCount) {
                    val hapticFeedback = rememberHapticFeedback()
                    LaunchedEffect(slider.value) {
                        if (sliderDragged) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.MOVE)
                        }
                    }
                }
                Slider(
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    state = slider,
                    onValueChange = onSliderValueChange,
                    onValueChangeFinished = onSliderValueChangeFinished,
                    maxTickCount = maxTickCount,
                    interactionSource = interactionSource,
                )
                RollingNumberPlaceholder(totalPages)
            }
        }
    }
}
