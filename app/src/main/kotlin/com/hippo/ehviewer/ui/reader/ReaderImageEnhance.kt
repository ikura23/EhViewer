package com.hippo.ehviewer.ui.reader

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 阅读器实时 GPU 画质增强模式。
 */
internal enum class ReaderImageEnhanceMode(val prefValue: Int) {
    OFF(0),
    SHARPEN_CAS(1),
    ANIME_ENHANCE(2),
    ;

    companion object {
        fun fromPrefValue(value: Int): ReaderImageEnhanceMode =
            entries.firstOrNull { it.prefValue == value } ?: OFF
    }
}

internal fun isGpuImageEnhanceSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU

internal fun normalizeEnhanceStrength(percent: Int): Float =
    percent.coerceIn(10, 100) / 100f

/**
 * 根据绘制尺寸与原图固有尺寸计算量化后的采样步长，避免缩放微调导致频繁重建 RenderEffect。
 */
internal fun computeEnhanceTexelStep(
    drawnWidth: Float,
    drawnHeight: Float,
    srcWidth: Float,
    srcHeight: Float,
): Float {
    if (!drawnWidth.isFinite() || !drawnHeight.isFinite() ||
        !srcWidth.isFinite() || !srcHeight.isFinite() ||
        drawnWidth <= 0f || drawnHeight <= 0f || srcWidth <= 0f || srcHeight <= 0f
    ) {
        return 1.0f
    }
    val scale = max(drawnWidth / srcWidth, drawnHeight / srcHeight)
    if (scale <= 1.05f) {
        return 1.0f
    }
    val clamped = scale.coerceIn(1.0f, 3.0f)
    return ((clamped * 10f).roundToInt() / 10f).coerceIn(1.0f, 3.0f)
}

/**
 * 方案 1：轻量双三次风格重采样抗锯齿 + AMD FidelityFX CAS 对比度自适应锐化。
 * 适合真人/CG/动图通用清晰化，只增强真实边缘而不放大 WebP 压缩噪点。
 */
internal const val CAS_SHARPEN_AGSL = """
uniform shader content;
uniform float strength;
uniform float texelStep;

half4 main(float2 fragCoord) {
    float4 center = float4(content.eval(fragCoord));
    float s = texelStep;

    float3 n = float3(content.eval(fragCoord + float2(0.0, -s)).rgb);
    float3 s0 = float3(content.eval(fragCoord + float2(0.0, s)).rgb);
    float3 w = float3(content.eval(fragCoord + float2(-s, 0.0)).rgb);
    float3 e = float3(content.eval(fragCoord + float2(s, 0.0)).rgb);

    float3 nw = float3(content.eval(fragCoord + float2(-s, -s)).rgb);
    float3 ne = float3(content.eval(fragCoord + float2(s, -s)).rgb);
    float3 sw = float3(content.eval(fragCoord + float2(-s, s)).rgb);
    float3 se = float3(content.eval(fragCoord + float2(s, s)).rgb);

    float3 c = center.rgb;
    float3 crossAvg = (n + s0 + w + e) * 0.25;
    float3 diagAvg = (nw + ne + sw + se) * 0.25;
    float upscaleBlend = 0.14 * clamp((s - 1.0) * 0.5, 0.0, 1.0);
    float3 base = mix(c, crossAvg * 0.65 + diagAvg * 0.35, upscaleBlend);

    float3 mn = min(min(min(n, s0), min(w, e)), c);
    float3 mx = max(max(max(n, s0), max(w, e)), c);
    float3 mn2 = min(mn, min(min(nw, ne), min(sw, se)));
    float3 mx2 = max(mx, max(max(nw, ne), max(sw, se)));
    mn = (mn + mn2) * 0.5;
    mx = (mx + mx2) * 0.5;

    float3 rcpM = float3(1.0) / max(mx, float3(1e-4));
    float3 amp = sqrt(clamp(min(mn, float3(1.0) - mx) * rcpM, float3(0.0), float3(1.0)));
    float peak = -1.0 / mix(8.0, 5.0, strength);
    float3 wCas = amp * peak;
    float3 rcpW = float3(1.0) / (float3(1.0) + 4.0 * wCas);
    float3 outRgb = (base + (n + s0 + w + e) * wCas) * rcpW;
    return half4(clamp(outRgb, float3(0.0), float3(center.a)), center.a);
}
"""

/**
 * 方案 2：轻量单 Pass Anime4K 风格边缘线条收细加深 + 平坦区保边去块 + CAS 锐化。
 * 针对二次元插画与低清 WebP 动图收紧虚化描边并削弱压缩块效应。
 */
internal const val ANIME_ENHANCE_AGSL = """
uniform shader content;
uniform float strength;
uniform float texelStep;

half4 main(float2 fragCoord) {
    float4 center = float4(content.eval(fragCoord));
    float s = texelStep;

    float3 n = float3(content.eval(fragCoord + float2(0.0, -s)).rgb);
    float3 s0 = float3(content.eval(fragCoord + float2(0.0, s)).rgb);
    float3 w = float3(content.eval(fragCoord + float2(-s, 0.0)).rgb);
    float3 e = float3(content.eval(fragCoord + float2(s, 0.0)).rgb);

    float3 nw = float3(content.eval(fragCoord + float2(-s, -s)).rgb);
    float3 ne = float3(content.eval(fragCoord + float2(s, -s)).rgb);
    float3 sw = float3(content.eval(fragCoord + float2(-s, s)).rgb);
    float3 se = float3(content.eval(fragCoord + float2(s, s)).rgb);

    float3 c = center.rgb;
    float3 lumaCoef = float3(0.299, 0.587, 0.114);
    float nwL = dot(nw, lumaCoef);
    float nL = dot(n, lumaCoef);
    float neL = dot(ne, lumaCoef);
    float wL = dot(w, lumaCoef);
    float eL = dot(e, lumaCoef);
    float swL = dot(sw, lumaCoef);
    float sL = dot(s0, lumaCoef);
    float seL = dot(se, lumaCoef);

    float gx = (neL + 2.0 * eL + seL) - (nwL + 2.0 * wL + swL);
    float gy = (swL + 2.0 * sL + seL) - (nwL + 2.0 * nL + neL);
    float grad = length(float2(gx, gy));
    float edgeMask = smoothstep(0.04, 0.22, grad);

    float2 dir = grad > 1e-4 ? (float2(gx, gy) / grad) : float2(0.0);
    float3 p1 = float3(content.eval(fragCoord + dir * (s * 0.65)).rgb);
    float3 p2 = float3(content.eval(fragCoord - dir * (s * 0.65)).rgb);
    float3 darkLine = min(min(p1, p2), c);
    float3 thin = mix(c, darkLine, edgeMask * (0.28 + 0.34 * strength));

    float3 flatSmooth = mix(c, (n + s0 + w + e) * 0.25, (1.0 - edgeMask) * 0.22 * strength);
    float3 base = mix(flatSmooth, thin, edgeMask);

    float3 mn = min(min(min(n, s0), min(w, e)), c);
    float3 mx = max(max(max(n, s0), max(w, e)), c);
    float3 mn2 = min(mn, min(min(nw, ne), min(sw, se)));
    float3 mx2 = max(mx, max(max(nw, ne), max(sw, se)));
    mn = (mn + mn2) * 0.5;
    mx = (mx + mx2) * 0.5;

    float3 rcpM = float3(1.0) / max(mx, float3(1e-4));
    float3 amp = sqrt(clamp(min(mn, float3(1.0) - mx) * rcpM, float3(0.0), float3(1.0)));
    float peak = -1.0 / mix(8.5, 5.2, strength);
    float3 wCas = amp * peak;
    float3 rcpW = float3(1.0) / (float3(1.0) + 4.0 * wCas);
    float3 outRgb = (base + (n + s0 + w + e) * wCas) * rcpW;
    return half4(clamp(outRgb, float3(0.0), float3(center.a)), center.a);
}
"""

@Composable
internal fun rememberImageEnhanceRenderEffect(
    mode: ReaderImageEnhanceMode,
    strengthPercent: Int,
    drawnSize: IntSize,
    intrinsicSize: Size,
): RenderEffect? {
    if (mode == ReaderImageEnhanceMode.OFF || !isGpuImageEnhanceSupported()) {
        return null
    }
    return rememberGpuRenderEffectApi33(
        mode = mode,
        strengthPercent = strengthPercent,
        drawnSize = drawnSize,
        intrinsicSize = intrinsicSize,
    )
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun rememberGpuRenderEffectApi33(
    mode: ReaderImageEnhanceMode,
    strengthPercent: Int,
    drawnSize: IntSize,
    intrinsicSize: Size,
): RenderEffect? {
    val shaderSource = when (mode) {
        ReaderImageEnhanceMode.OFF -> return null
        ReaderImageEnhanceMode.SHARPEN_CAS -> CAS_SHARPEN_AGSL
        ReaderImageEnhanceMode.ANIME_ENHANCE -> ANIME_ENHANCE_AGSL
    }
    val shader = remember(shaderSource) {
        runCatching { RuntimeShader(shaderSource) }.getOrNull()
    } ?: return null

    val strength = normalizeEnhanceStrength(strengthPercent)
    val srcW = if (intrinsicSize.isSpecified) intrinsicSize.width else 0f
    val srcH = if (intrinsicSize.isSpecified) intrinsicSize.height else 0f
    val texelStep = computeEnhanceTexelStep(
        drawnWidth = drawnSize.width.toFloat(),
        drawnHeight = drawnSize.height.toFloat(),
        srcWidth = srcW,
        srcHeight = srcH,
    )

    return remember(shader, strength, texelStep) {
        shader.setFloatUniform("strength", strength)
        shader.setFloatUniform("texelStep", texelStep)
        AndroidRenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
}
