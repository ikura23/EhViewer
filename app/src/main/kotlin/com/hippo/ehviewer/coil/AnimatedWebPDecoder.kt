package com.hippo.ehviewer.coil

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.ByteBufferMetadata
import coil3.decode.ContentMetadata
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.gif.isAnimatedWebP
import coil3.request.Options
import coil3.size.Dimension
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import okio.FileSystem

/**
 * WebP 动图解码器，会将目标绘制尺寸透传给 [AnimatedWebPDrawable] 以支持缩略图降采样。
 */
class AnimatedWebPDecoder(
    private val source: ByteBuffer,
    private val targetWidth: Int? = null,
    private val targetHeight: Int? = null,
) : Decoder {
    override suspend fun decode() = DecodeResult(
        AnimatedWebPDrawable(source, targetWidth, targetHeight).asImage(),
        false,
    )

    object Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ) = if (DecodeUtils.isAnimatedWebP(result.source.source())) {
            val targetWidth = (options.size.width as? Dimension.Pixels)?.px
            val targetHeight = (options.size.height as? Dimension.Pixels)?.px
            result.source.toByteBufferOrNull()?.let {
                AnimatedWebPDecoder(it, targetWidth, targetHeight)
            }
        } else {
            null
        }
    }
}

private fun ImageSource.toByteBufferOrNull(): ByteBuffer? {
    if (fileSystem === FileSystem.SYSTEM) {
        val file = fileOrNull()
        if (file != null) {
            return file.toFile().inputStream().mapReadOnly()
        }
    }
    return when (val metadata = metadata) {
        is ContentMetadata -> metadata.assetFileDescriptor.createInputStream().mapReadOnly()
        is ByteBufferMetadata -> metadata.byteBuffer
        else -> null
    }
}

private fun FileInputStream.mapReadOnly(): ByteBuffer = channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
