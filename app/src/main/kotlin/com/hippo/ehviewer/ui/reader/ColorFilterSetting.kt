package com.hippo.ehviewer.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness5
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.alpha
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.RollingNumber
import com.ehviewer.core.ui.component.rememberIntPrefSliderState
import com.ehviewer.core.ui.component.rememberIntSliderState
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.collectAsState

/**
 * 护眼快捷预设，避免手动调节 ARGB 四通道滑块。
 */
internal enum class ReaderColorFilterPreset(val argb: Int, val mode: Int) {
    WARM_EYE_CARE(packArgb(a = 52, r = 255, g = 214, b = 140), 0),
    SOFT_DIM(packArgb(a = 90, r = 32, g = 28, b = 24), 0),
}

internal fun packArgb(a: Int, r: Int, g: Int, b: Int): Int =
    ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

internal fun detectActiveColorFilterPreset(enabled: Boolean, argb: Int, mode: Int): ReaderColorFilterPreset? {
    if (!enabled) return null
    return ReaderColorFilterPreset.entries.firstOrNull { it.argb == argb && it.mode == mode }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorFilterSetting() = Column(modifier = Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
    val enhanceModeRaw by Settings.imageEnhanceMode.collectAsState()
    val activeEnhanceMode = ReaderImageEnhanceMode.fromPrefValue(enhanceModeRaw)
    val enhanceSupported = isGpuImageEnhanceSupported()
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(id = R.string.pref_image_enhance),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = activeEnhanceMode == ReaderImageEnhanceMode.OFF,
                enabled = enhanceSupported,
                onClick = { Settings.imageEnhanceMode.value = ReaderImageEnhanceMode.OFF.prefValue },
                label = { Text(text = stringResource(id = R.string.image_enhance_off)) },
            )
            FilterChip(
                selected = activeEnhanceMode == ReaderImageEnhanceMode.SHARPEN_CAS,
                enabled = enhanceSupported,
                onClick = { Settings.imageEnhanceMode.value = ReaderImageEnhanceMode.SHARPEN_CAS.prefValue },
                label = { Text(text = stringResource(id = R.string.image_enhance_sharpen)) },
            )
            FilterChip(
                selected = activeEnhanceMode == ReaderImageEnhanceMode.ANIME_ENHANCE,
                enabled = enhanceSupported,
                onClick = { Settings.imageEnhanceMode.value = ReaderImageEnhanceMode.ANIME_ENHANCE.prefValue },
                label = { Text(text = stringResource(id = R.string.image_enhance_anime)) },
            )
        }
        if (!enhanceSupported) {
            Text(
                text = stringResource(id = R.string.image_enhance_requires_android_13),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    AnimatedVisibility(visible = enhanceSupported && activeEnhanceMode != ReaderImageEnhanceMode.OFF) {
        val enhanceStrength = rememberIntPrefSliderState(Settings.imageEnhanceStrength, 10..100)
        SliderChoice(
            startSlot = { Text(text = stringResource(id = R.string.pref_image_enhance_strength)) },
            endSlot = { RollingNumber(number = enhanceStrength.value, length = 3) },
            state = enhanceStrength,
        )
    }
    val customBrightness = Settings.customBrightness.asMutableState()
    SwitchChoice(
        title = stringResource(id = R.string.pref_custom_brightness),
        field = customBrightness,
    )
    AnimatedVisibility(visible = customBrightness.value) {
        val brightness = rememberIntPrefSliderState(Settings.customBrightnessValue, -75..100)
        SliderChoice(
            startSlot = { Icon(imageVector = Icons.Default.Brightness5, contentDescription = null) },
            endSlot = { RollingNumber(number = brightness.value, length = 3) },
            state = brightness,
        )
    }
    val colorFilter = Settings.colorFilter.asMutableState()
    val colorFilterValue by Settings.colorFilterValue.collectAsState()
    val colorFilterMode by Settings.colorFilterMode.collectAsState()
    val activePreset = detectActiveColorFilterPreset(colorFilter.value, colorFilterValue, colorFilterMode)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(id = R.string.pref_color_filter_presets),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = activePreset == ReaderColorFilterPreset.WARM_EYE_CARE,
                onClick = {
                    val preset = ReaderColorFilterPreset.WARM_EYE_CARE
                    Settings.colorFilterMode.value = preset.mode
                    Settings.colorFilterValue.value = preset.argb
                    Settings.colorFilter.value = true
                },
                label = { Text(text = stringResource(id = R.string.color_filter_preset_warm)) },
            )
            FilterChip(
                selected = activePreset == ReaderColorFilterPreset.SOFT_DIM,
                onClick = {
                    val preset = ReaderColorFilterPreset.SOFT_DIM
                    Settings.colorFilterMode.value = preset.mode
                    Settings.colorFilterValue.value = preset.argb
                    Settings.colorFilter.value = true
                },
                label = { Text(text = stringResource(id = R.string.color_filter_preset_soft_dim)) },
            )
            FilterChip(
                selected = !colorFilter.value,
                onClick = {
                    Settings.colorFilter.value = false
                    Settings.colorFilterMode.value = 0
                },
                label = { Text(text = stringResource(id = R.string.color_filter_preset_reset)) },
            )
        }
    }
    SwitchChoice(
        title = stringResource(id = R.string.pref_custom_color_filter),
        field = colorFilter,
    )
    AnimatedVisibility(visible = colorFilter.value) {
        val r = rememberIntSliderState(colorFilterValue.red, 0..255)
        val g = rememberIntSliderState(colorFilterValue.green, 0..255)
        val b = rememberIntSliderState(colorFilterValue.blue, 0..255)
        val a = rememberIntSliderState(colorFilterValue.alpha, 0..255)
        // 先跟随外部（如一键预设）同步滑块状态，再在用户拖动滑块时写回设置，防止旧滑块值反覆盖预设。
        LaunchedEffect(colorFilterValue) {
            if (r.value != colorFilterValue.red) r.value = colorFilterValue.red
            if (g.value != colorFilterValue.green) g.value = colorFilterValue.green
            if (b.value != colorFilterValue.blue) b.value = colorFilterValue.blue
            if (a.value != colorFilterValue.alpha) a.value = colorFilterValue.alpha
        }
        LaunchedEffect(r.value, g.value, b.value, a.value) {
            val packed = packArgb(a = a.value, r = r.value, g = g.value, b = b.value)
            if (packed != Settings.colorFilterValue.value) {
                Settings.colorFilterValue.value = packed
            }
        }
        Column {
            SliderChoice(
                startSlot = { Text(text = "R") },
                endSlot = { RollingNumber(number = r.value, length = 3) },
                state = r,
            )
            SliderChoice(
                startSlot = { Text(text = "G") },
                endSlot = { RollingNumber(number = g.value, length = 3) },
                state = g,
            )
            SliderChoice(
                startSlot = { Text(text = "B") },
                endSlot = { RollingNumber(number = b.value, length = 3) },
                state = b,
            )
            SliderChoice(
                startSlot = { Text(text = "A") },
                endSlot = { RollingNumber(number = a.value, length = 3) },
                state = a,
            )
        }
    }
    SpinnerChoice(
        title = stringResource(id = R.string.pref_color_filter_mode),
        entries = stringArrayResource(id = com.hippo.ehviewer.R.array.color_filter_modes),
        values = [0, 1, 2, 3, 4, 5],
        field = Settings.colorFilterMode.asMutableState(),
    )
    SwitchChoice(
        title = stringResource(id = R.string.pref_grayscale),
        field = Settings.grayScale.asMutableState(),
    )
    SwitchChoice(
        title = stringResource(id = R.string.pref_inverted_colors),
        field = Settings.invertedColors.asMutableState(),
    )
}
