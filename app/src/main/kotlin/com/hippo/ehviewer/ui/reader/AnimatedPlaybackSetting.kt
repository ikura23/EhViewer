package com.hippo.ehviewer.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val MIN_PLAYBACK_SPEED = 0.1f
internal const val MAX_PLAYBACK_SPEED = 10f

internal val PLAYBACK_SPEED_PRESETS = listOf(
    0.25f,
    0.5f,
    0.75f,
    1f,
    1.25f,
    1.5f,
    2f,
    2.5f,
    3f,
    4f,
)

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
    var speed by Settings.animatedPlaybackSpeed.asMutableState()
    var dialog by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf(formatPlaybackSpeedNumber(speed)) }
    var showInvalidError by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).clickable {
            customText = formatPlaybackSpeedNumber(speed)
            showInvalidError = false
            dialog = true
        }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(id = R.string.pref_animated_playback_speed),
            modifier = Modifier.weight(0.5f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.weight(0.5f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatPlaybackSpeed(speed),
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        }
    }
    if (dialog) {
        AlertDialog(
            onDismissRequest = { dialog = false },
            title = { Text(text = stringResource(id = R.string.pref_animated_playback_speed)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    PLAYBACK_SPEED_PRESETS.forEach { preset ->
                        val selected = abs(speed - preset) < 0.005f
                        Row(
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                                .clickable {
                                    speed = preset
                                    dialog = false
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = {
                                    speed = preset
                                    dialog = false
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = formatPlaybackSpeed(preset),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = stringResource(id = R.string.animated_playback_speed_custom),
                        modifier = Modifier.padding(bottom = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    // 固定在左侧显示前缀 "x"，用户只需填纯数字小数
                    OutlinedTextField(
                        value = customText,
                        onValueChange = {
                            customText = sanitizePlaybackSpeedInput(it)
                            showInvalidError = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        prefix = { Text(text = "x") },
                        placeholder = { Text(text = stringResource(id = R.string.animated_playback_speed_custom_hint)) },
                        isError = showInvalidError,
                        supportingText = if (showInvalidError) {
                            { Text(text = stringResource(id = R.string.animated_playback_speed_invalid)) }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val parsed = parseCustomPlaybackSpeed(customText)
                    if (parsed != null) {
                        speed = parsed
                        dialog = false
                    } else {
                        showInvalidError = true
                    }
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

/**
 * 格式化倍率为不含前缀 `x` 的纯数字文本（保留至多两位小数并去除多余尾随零）。
 */
internal fun formatPlaybackSpeedNumber(value: Float): String {
    val safe = if (value.isFinite()) value.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED) else 1f
    val hundredths = (safe * 100f).roundToInt()
    val integerPart = hundredths / 100
    val fracPart = hundredths % 100
    return when {
        fracPart == 0 -> integerPart.toString()
        fracPart % 10 == 0 -> "$integerPart.${fracPart / 10}"
        fracPart < 10 -> "$integerPart.0$fracPart"
        else -> "$integerPart.$fracPart"
    }
}

/**
 * 格式化展示用倍率标签，如 `x0.5`、`x1`、`x2`。
 */
internal fun formatPlaybackSpeed(value: Float): String = "x${formatPlaybackSpeedNumber(value)}"

/**
 * 过滤自定义倍率输入，仅保留数字和至多一个小数点，自动剔除 `x`/`X`/空格等非数字符号。
 */
internal fun sanitizePlaybackSpeedInput(raw: String): String {
    val sb = StringBuilder(raw.length)
    var hasDot = false
    for (ch in raw) {
        when {
            ch in '0'..'9' -> sb.append(ch)
            ch == '.' && !hasDot -> {
                hasDot = true
                if (sb.isEmpty()) sb.append('0')
                sb.append('.')
            }
        }
    }
    return sb.toString()
}

/**
 * 解析自定义倍率为合法浮点值，仅接受 `[0.1, 10]` 区间内的有限正数。
 */
internal fun parseCustomPlaybackSpeed(raw: String): Float? {
    val clean = sanitizePlaybackSpeedInput(raw)
    val parsed = clean.toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
    if (parsed < MIN_PLAYBACK_SPEED || parsed > MAX_PLAYBACK_SPEED) return null
    return parsed
}

private val preloadCounts = listOf(1, 2, 3, 5)
