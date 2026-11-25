package com.hippo.ehviewer.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState

@Composable
fun AnimatedPlaybackSetting() {
    val attached = AnimatedPlaybackSession.attached
    val active = AnimatedPlaybackSession.active
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp)
            .clickable(enabled = attached) {
                AnimatedPlaybackSession.manualOverride = !active
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(id = R.string.pref_animated_playback),
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = active,
            enabled = attached,
            onCheckedChange = {
                AnimatedPlaybackSession.manualOverride = it
            },
        )
    }
    val autoDetect = Settings.animatedPlayback.asMutableState()
    SwitchChoice(
        title = stringResource(id = R.string.pref_animated_playback_auto),
        field = autoDetect,
    )
    AnimatedVisibility(visible = autoDetect.value || active) {
        Column {
            SwitchChoice(
                title = stringResource(id = R.string.pref_animated_playback_neighbors),
                field = Settings.animatedPlaybackNeighbors.asMutableState(),
            )
            val preloadEntries = preloadCounts.map {
                stringResource(id = R.string.animated_playback_preload_pages, it)
            }.toTypedArray()
            SpinnerChoice(
                title = stringResource(id = R.string.pref_animated_playback_preload),
                entries = preloadEntries,
                values = preloadCounts,
                field = Settings.animatedPlaybackPreload.asMutableState(),
            )
            PlaybackSpeedChoice()
        }
    }
}

@Composable
private fun PlaybackSpeedChoice() {
    val speed = Settings.animatedPlaybackSpeed.asMutableState()
    var dialog by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(formatPlaybackSpeed(speed.value)) }
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).clickable {
            text = formatPlaybackSpeed(speed.value)
            dialog = true
        }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(id = R.string.pref_animated_playback_speed),
            modifier = Modifier.weight(1f),
        )
        Text(text = formatPlaybackSpeed(speed.value))
    }
    if (dialog) {
        AlertDialog(
            onDismissRequest = { dialog = false },
            title = { Text(text = stringResource(id = R.string.pref_animated_playback_speed)) },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(0.5f, 1f, 1.5f, 2f).forEach { preset ->
                            TextButton(onClick = {
                                text = formatPlaybackSpeed(preset)
                            }) {
                                Text(text = formatPlaybackSpeed(preset))
                            }
                        }
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // The field is pre-filled and the presets insert values like "2x",
                    // so the trailing unit must be stripped before parsing.
                    val parsed = text.trim()
                        .removeSuffix("x")
                        .removeSuffix("X")
                        .trim()
                        .toFloatOrNull()
                    parsed?.takeIf { it.isFinite() }?.let {
                        speed.value = it.coerceIn(0.1f, 5f)
                    }
                    dialog = false
                }) {
                    Text(text = stringResource(id = R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { dialog = false }) {
                    Text(text = stringResource(id = R.string.action_cancel))
                }
            },
        )
    }
}

private fun formatPlaybackSpeed(value: Float): String {
    val safe = if (value.isFinite()) value.coerceIn(0.1f, 5f) else 1f
    val rounded = (safe * 100).toInt() / 100f
    return if (rounded % 1f == 0f) "${rounded.toInt()}x" else "${rounded}x"
}

private val preloadCounts = listOf(1, 2, 3, 5)
