package com.hippo.ehviewer.ui.main

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadThumbCacheTest {
    @Test
    fun findsAnimatedArchiveThumbBeforeStaticAndHonorsAnimatedOnly() {
        val dir = Files.createTempDirectory("ehv-thumb-cache").toFile()
        try {
            val prefix = "1001-deadbeef"
            val jpgFile = File(dir, "$prefix.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val gifFile = File(dir, "$prefix.gif").apply {
                writeBytes("GIF89a......".toByteArray(Charsets.US_ASCII))
            }

            // 有真动图缓存时，无论 animatedOnly 为何均优先命中动图
            assertEquals(gifFile, findCachedArchiveThumb(dir, prefix, animatedOnly = false) { true })
            assertEquals(gifFile, findCachedArchiveThumb(dir, prefix, animatedOnly = true) { true })

            // 移除动图后，animatedOnly = true 返回 null；animatedOnly = false 命中静态 jpg
            gifFile.delete()
            assertNull(findCachedArchiveThumb(dir, prefix, animatedOnly = true) { true })
            assertEquals(jpgFile, findCachedArchiveThumb(dir, prefix, animatedOnly = false) { true })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun memoryCacheInvalidatesWhenTargetFileChangesAndEvictsLruEntries() {
        val dir = Files.createTempDirectory("ehv-mem-cache").toFile()
        try {
            val f1 = File(dir, "1.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val key1 = LocalThumbCacheKey(1L, "dir-1", false)
            val cache = LocalThumbMemoryCache(maxEntries = 2)
            cache.put(key1, LocalThumbResult(f1, "k1"))
            assertNotNull(cache.get(key1))

            // 模拟磁盘文件大小变更，内存缓存应自动失效
            f1.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))
            assertNull(cache.get(key1))

            // 容量超出 2 时淘汰最早访问项
            val f2 = File(dir, "2.jpg").apply { writeBytes(byteArrayOf(9, 9)) }
            val f3 = File(dir, "3.jpg").apply { writeBytes(byteArrayOf(8, 8)) }
            val key2 = LocalThumbCacheKey(2L, "dir-2", false)
            val key3 = LocalThumbCacheKey(3L, "dir-3", false)
            cache.put(key1, LocalThumbResult(f1, "k1-new"))
            cache.put(key2, LocalThumbResult(f2, "k2"))
            cache.get(key1) // 刷新 key1 为最近访问
            cache.put(key3, LocalThumbResult(f3, "k3"))

            assertEquals(2, cache.size())
            assertNotNull(cache.get(key1))
            assertNull(cache.get(key2))
            assertNotNull(cache.get(key3))
        } finally {
            dir.deleteRecursively()
        }
    }
}
