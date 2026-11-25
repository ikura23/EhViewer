package com.hippo.ehviewer.download

import com.ehviewer.core.database.model.DownloadEntity
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.database.model.GalleryEntity
import com.hippo.ehviewer.coil.resolveGalleryThumbCacheKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGalleryScannerTest {
    private val storageRoot = "/storage/emulated/0"

    @Test
    fun resolvesAbsoluteDownloadLocation() {
        assertEquals(
            "/storage/emulated/0/Download/EhViewer/",
            resolveDownloadLocationPath("/storage/emulated/0/Download/EhViewer/", storageRoot)?.plus("/"),
        )
        assertEquals(
            "/storage/emulated/0/MyManga",
            resolveDownloadLocationPath("/storage/emulated/0/MyManga/", storageRoot),
        )
    }

    @Test
    fun resolvesSafTreeAndDocumentUriLocations() {
        val treeOnly = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FEhViewer"
        assertEquals(
            "/storage/emulated/0/Download/EhViewer",
            resolveDownloadLocationPath(treeOnly, storageRoot),
        )

        val treeWithDocument = "content://com.android.externalstorage.documents/tree/primary%3A%E6%BC%AB%E7%94%BB/document/primary%3A%E6%BC%AB%E7%94%BB%2FEhDownloads"
        assertEquals(
            "/storage/emulated/0/漫画/EhDownloads",
            resolveDownloadLocationPath(treeWithDocument, storageRoot),
        )
    }

    @Test
    fun resolvesSecondaryVolumeAndHandlesUnsupportedLocations() {
        val sdUri = "content://com.android.externalstorage.documents/tree/1234-5678%3AComics%2FEh"
        assertEquals("/storage/1234-5678/Comics/Eh", resolveDownloadLocationPath(sdUri, storageRoot))
        assertNull(resolveDownloadLocationPath("content://com.other.provider/tree/abc", storageRoot))
        assertNull(resolveDownloadLocationPath("", storageRoot))
    }

    @Test
    fun importsDirectChildInsideConfiguredDownloadDirWithoutLocalLabelAndWithRelativeDirname() {
        val configured = "/storage/emulated/0/MyManga/EhViewer"
        val target = resolveImportTarget(
            entryCanonicalPath = "/storage/emulated/0/MyManga/EhViewer/123456-abcdef",
            configuredDownloadRoot = configured,
            appDownloadRoots = listOf(configured),
            isArchive = false,
        )
        assertTrue(target.isInsideAppDownloadDir)
        assertNull(target.label)
        assertEquals("123456-abcdef", target.dirname)
    }

    @Test
    fun importsNestedDirOrArchiveInsideAppDownloadDirWithoutLocalLabel() {
        val configured = "/storage/emulated/0/MyManga"
        val nestedTarget = resolveImportTarget(
            entryCanonicalPath = "/storage/emulated/0/MyManga/2026/123456-abcdef",
            configuredDownloadRoot = configured,
            appDownloadRoots = listOf(configured),
            isArchive = false,
        )
        assertTrue(nestedTarget.isInsideAppDownloadDir)
        assertNull(nestedTarget.label)
        assertEquals("/storage/emulated/0/MyManga/2026/123456-abcdef", nestedTarget.dirname)

        val archiveTarget = resolveImportTarget(
            entryCanonicalPath = "/storage/emulated/0/MyManga/sample.cbz",
            configuredDownloadRoot = configured,
            appDownloadRoots = listOf(configured),
            isArchive = true,
        )
        assertTrue(archiveTarget.isInsideAppDownloadDir)
        assertNull(archiveTarget.label)
        assertEquals("/storage/emulated/0/MyManga/sample.cbz", archiveTarget.dirname)
    }

    @Test
    fun importsExternalDirectoryWithLocalLabelAndAbsolutePath() {
        val configured = "/storage/emulated/0/MyManga"
        val target = resolveImportTarget(
            entryCanonicalPath = "/storage/emulated/0/Download/OtherFork/999999-test",
            configuredDownloadRoot = configured,
            appDownloadRoots = listOf(configured),
            isArchive = false,
        )
        assertFalse(target.isInsideAppDownloadDir)
        assertEquals("本地", target.label)
        assertEquals("/storage/emulated/0/Download/OtherFork/999999-test", target.dirname)
    }

    @Test
    fun isolatesLocalThumbCacheKeyPerGidWhilePreservingRemoteKey() {
        assertEquals("ab/cd123456.jpg", resolveGalleryThumbCacheKey(1001L, "ab/cd123456.jpg"))
        assertEquals("local-thumb:1001:00000001.jpg", resolveGalleryThumbCacheKey(1001L, "00000001.jpg"))
        assertEquals("local-thumb:2002:00000001.jpg", resolveGalleryThumbCacheKey(2002L, "00000001.jpg"))
    }

    @Test
    fun detectsAnimatedGifAndAnimatedWebpHeadersOnly() {
        val gif89a = "GIF89a...".toByteArray(Charsets.US_ASCII)
        val gif87a = "GIF87a...".toByteArray(Charsets.US_ASCII)
        assertTrue(isAnimatedImageHeader(gif89a))
        assertTrue(isAnimatedImageHeader(gif87a))

        val animatedWebp = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            36, 0, 0, 0,
            'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
            'V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), 'X'.code.toByte(),
            10, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            'A'.code.toByte(), 'N'.code.toByte(), 'I'.code.toByte(), 'M'.code.toByte(),
            16, 0, 0, 0,
        )
        assertTrue(isAnimatedImageHeader(animatedWebp))

        val staticWebp = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            20, 0, 0, 0,
            'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
            'V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), 'L'.code.toByte(),
            4, 0, 0, 0,
            0, 0, 0, 0,
        )
        assertFalse(isAnimatedImageHeader(staticWebp))

        val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertFalse(isAnimatedImageHeader(jpegHeader))
    }

    @Test
    fun validatesStrictTenCharHexGalleryToken() {
        assertTrue(isValidGalleryHexToken("a1b2c3d4e5"))
        assertTrue(isValidGalleryHexToken("0123456789"))
        assertTrue(isValidGalleryHexToken("ABCDEF1234"))
        assertFalse(isValidGalleryHexToken("[Ka..."))
        assertFalse(isValidGalleryHexToken("abc123"))
        assertFalse(isValidGalleryHexToken(""))
        assertFalse(isValidGalleryHexToken(null))
    }

    @Test
    fun mergesManualOrderForSubsetWithoutDisturbingHiddenEntries() {
        val global = listOf(10L, 20L, 30L, 40L, 50L)
        // 仅筛选到 [20, 40, 50] 并拖动成 [50, 20, 40]，10 与 30 应留在各自原槽位
        val merged = mergeManualOrder(global, listOf(50L, 20L, 40L))
        assertEquals(listOf(10L, 50L, 30L, 20L, 40L), merged)

        val fullMerged = mergeManualOrder(global, listOf(50L, 40L, 30L, 20L, 10L))
        assertEquals(listOf(50L, 40L, 30L, 20L, 10L), fullMerged)
    }

    @Test
    fun roundTripsCustomSortModeAndPlacesUnrankedNewDownloadsFirst() {
        val custom = SortMode(SortMode.Field.CUSTOM, SortMode.Order.ASC, groupByDownloadLabel = true)
        assertEquals(custom, SortMode.from(custom.flag))

        fun dummyInfo(gid: Long, time: Long): DownloadInfo = DownloadInfo(
            galleryInfo = GalleryEntity(
                gid = gid,
                token = "0123456789",
                title = "G$gid",
                titleJpn = null,
                thumbKey = null,
                category = 0,
                posted = null,
                uploader = null,
                rating = 0f,
                simpleTags = null,
                pages = 1,
                simpleLanguage = null,
                favoriteSlot = -2,
            ),
            dirname = "dir-$gid",
            downloadInfo = DownloadEntity(gid = gid, time = time),
        )

        val item1 = dummyInfo(1L, 100L)
        val item2 = dummyInfo(2L, 200L)
        val newUnranked = dummyInfo(3L, 300L)
        val ranks = mapOf(2L to 0, 1L to 1)
        val sorted = listOf(item1, item2, newUnranked)
            .sortedWith(SortMode(SortMode.Field.CUSTOM, SortMode.Order.ASC).comparator(ranks))
        assertEquals(listOf(3L, 2L, 1L), sorted.map { it.gid })
    }
}
