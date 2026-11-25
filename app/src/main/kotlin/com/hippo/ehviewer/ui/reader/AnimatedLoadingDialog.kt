package com.hippo.ehviewer.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ehviewer.core.i18n.R
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AnimatedLoadingDialog(
    loaded: Int,
    target: Int,
    readyToDismiss: Boolean = true,
    onClose: () -> Unit,
) {
    val safeTarget = target.coerceAtLeast(1)
    val rawProgress = (loaded.toFloat() / safeTarget).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = tween(durationMillis = 480),
        label = "animated-loading-progress",
    )
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }

    fun dismissWithAnimation() {
        if (closing) return
        closing = true
        visible = false
        scope.launch {
            delay(220)
            onClose()
        }
    }

    LaunchedEffect(Unit) {
        visible = true
    }

    // Keep 100% on screen for a moment, then animate the card away by itself.
    LaunchedEffect(loaded, safeTarget, readyToDismiss) {
        if (loaded >= safeTarget && readyToDismiss && !closing) {
            delay(650)
            dismissWithAnimation()
        }
    }

    Dialog(onDismissRequest = { dismissWithAnimation() }) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(220)) +
                scaleIn(tween(260), initialScale = 0.92f) +
                slideInVertically(tween(280)) { it / 10 },
            exit = fadeOut(tween(180)) +
                scaleOut(tween(200), targetScale = 0.96f) +
                slideOutVertically(tween(200)) { it / 14 },
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 3.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(id = R.string.animated_loading_title),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        IconButton(onClick = { dismissWithAnimation() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier.size(104.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (loaded <= 0) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(104.dp),
                                strokeWidth = 8.dp,
                            )
                        } else {
                            CircularProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier.size(104.dp),
                                strokeWidth = 8.dp,
                            )
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = "$loaded / $safeTarget",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "${(animatedProgress * 100).roundToInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
