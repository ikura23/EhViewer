package com.hippo.ehviewer.download

import com.ehviewer.core.database.model.DownloadEntity
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.database.model.GalleryEntity
import com.hippo.ehviewer.coil.resolveGalleryThumbCacheKey
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        val comparator = SortMode(SortMode.Field.CUSTOM, SortMode.Order.ASC).comparator(ranks)
        val sorted = listOf(item1, item2, newUnranked)
            .sortedWith(comparator)
        assertEquals(listOf(3L, 2L, 1L), sorted.map { it.gid })

        // 在筛选子集 [2, 1] 中拖拽交换后，未出现的未排名新条目 3 应继续留在最前而不沉底
        val currentGlobalOrder = sorted.map { it.gid }
        val mergedAfterSubsetDrag = mergeManualOrder(currentGlobalOrder, listOf(1L, 2L))
        assertEquals(listOf(3L, 1L, 2L), mergedAfterSubsetDrag)
    }

    @Test
    fun avoidsFalsePositiveGalleryGidsForYearOrShortPrefixedFolders() {
        val yearTravel = resolveFolderGid(
            folderName = "2023-05 旅行",
            spiderGid = null,
            parsedGid = null,
            canonicalDirPath = "/storage/emulated/0/Download/2023-05 旅行",
        )
        val yearDinner = resolveFolderGid(
            folderName = "2023-08 聚餐",
            spiderGid = null,
            parsedGid = null,
            canonicalDirPath = "/storage/emulated/0/Download/2023-08 聚餐",
        )
        assertTrue(yearTravel < 0L)
        assertTrue(yearDinner < 0L)
        assertNotEquals(yearTravel, yearDinner)

        val realGallery = resolveFolderGid(
            folderName = "4203956-[Dynamic Gallery]",
            spiderGid = null,
            parsedGid = null,
            canonicalDirPath = "/storage/emulated/0/EhViewer/download/4203956-[Dynamic Gallery]",
        )
        assertEquals(4203956L, realGallery)

        val explicitMetaOverridesFolder = resolveFolderGid(
            folderName = "2023-05 合集",
            spiderGid = 999999L,
            parsedGid = null,
            canonicalDirPath = "/storage/emulated/0/Download/2023-05 合集",
        )
        assertEquals(999999L, explicitMetaOverridesFolder)
    }

    @Test
    fun enforcesStreamByteLimitWhenReadingSafStreams() {
        val withinLimit = ByteArray(1024) { 0x2A }
        val readOk = readStreamBytesLimited(ByteArrayInputStream(withinLimit), 1024L)
        assertEquals(1024, readOk?.size)

        val overLimit = ByteArray(1025) { 0x2A }
        assertNull(readStreamBytesLimited(ByteArrayInputStream(overLimit), 1024L))
        assertNull(readStreamBytesLimited(ByteArrayInputStream(ByteArray(0)), 1024L))
    }

    @Test
    fun filtersNonGalleryAppDirectoriesAndGenericDownloadRoots() {
        assertTrue(shouldSkipDirectoryName(".csj"))
        assertTrue(shouldSkipDirectoryName("MiBrowser"))
        assertTrue(shouldSkipDirectoryName("QuarkDownloads"))
        assertTrue(shouldSkipDirectoryName("QQ"))
        assertTrue(shouldSkipDirectoryName("DLManager"))
        assertTrue(shouldSkipDirectoryName("netease"))
        assertFalse(shouldSkipDirectoryName("EhViewer"))
        assertFalse(shouldSkipDirectoryName("4203956-[DyDy_cos] Animated Collection"))

        assertTrue(isGenericDownloadRootPath("/storage/emulated/0/Download", storageRoot))
        assertTrue(isGenericDownloadRootPath("/storage/emulated/0/download/", storageRoot))
        assertTrue(isGenericDownloadRootPath("/storage/emulated/0", storageRoot))
        assertFalse(isGenericDownloadRootPath("/storage/emulated/0/EhViewer/download", storageRoot))
    }

    @Test
    fun validatesGalleryDirectoriesAndArchivesStrictly() {
        assertTrue(isValidGalleryDirectoryEntries(listOf(".ehviewer", "00000001.webp"), hasExplicitMeta = true, allowSingleImage = false))
        assertFalse(isValidGalleryDirectoryEntries(listOf("photo.jpg"), hasExplicitMeta = false, allowSingleImage = false))
        assertTrue(isValidGalleryDirectoryEntries(listOf("photo.jpg"), hasExplicitMeta = false, allowSingleImage = true))
        assertFalse(isValidGalleryDirectoryEntries(listOf("1.png", "2.png", "manual.pdf"), hasExplicitMeta = false, allowSingleImage = false))
        assertTrue(isValidGalleryDirectoryEntries(listOf("01.jpg", "02.jpg", "notes.txt"), hasExplicitMeta = false, allowSingleImage = false))

        assertTrue(isValidGalleryArchiveEntries("sample.cbz", listOf("01.jpg")))
        assertFalse(isValidGalleryArchiveEntries("软件.zip", listOf("1.png", "2.png", "3.png", "app.apk")))
        assertFalse(isValidGalleryArchiveEntries("作业.zip", listOf("cover.jpg", "1.jpg", "report.docx", "script.py")))
        assertFalse(isValidGalleryArchiveEntries("双图.zip", listOf("1.jpg", "2.jpg")))
        assertTrue(isValidGalleryArchiveEntries("漫画.zip", listOf("1.jpg", "2.jpg", "3.jpg", "readme.txt")))
        assertTrue(isValidGalleryArchiveEntries("双图带元数据.zip", listOf(".ehviewer", "1.jpg")))
    }

    @Test
    fun resolvesArchiveGidFromPrefixAndDetectsPartialAppDownloads() {
        assertEquals(
            3715434L,
            resolveArchiveGid(
                fileName = "3715434-CunnyFunky 2025.12 GIF (to .zip",
                canonicalFilePath = "/storage/emulated/0/EhViewer/download/3715434-CunnyFunky 2025.12 GIF (to .zip",
            ),
        )
        val unnumberedArchive = resolveArchiveGid(
            fileName = "MyCollection.cbz",
            canonicalFilePath = "/storage/emulated/0/Comics/MyCollection.cbz",
        )
        assertTrue(unnumberedArchive < 0L)

        assertEquals(
            935 to DownloadInfo.STATE_NONE,
            resolveLocalDownloadCompletion(
                actualImages = 60,
                spiderPages = 935,
                isInsideAppDownloadDir = true,
                hasValidRemoteIdentity = true,
            ),
        )
        assertEquals(
            60 to DownloadInfo.STATE_FINISH,
            resolveLocalDownloadCompletion(
                actualImages = 60,
                spiderPages = 60,
                isInsideAppDownloadDir = true,
                hasValidRemoteIdentity = true,
            ),
        )
        assertEquals(
            60 to DownloadInfo.STATE_FINISH,
            resolveLocalDownloadCompletion(
                actualImages = 60,
                spiderPages = 935,
                isInsideAppDownloadDir = false,
                hasValidRemoteIdentity = true,
            ),
        )
    }

    @Test
    fun scanRunGateAllowsImmediateRestartAfterCancelAndIgnoresStaleFinish() = runBlocking {
        val gate = ScanRunGate()
        val job1 = Job()
        var gen1 = -1L
        var gen2 = -1L
        var seenPrevJob: Job? = null

        assertTrue(
            gate.tryStart { gen, prev ->
                gen1 = gen
                assertNull(prev)
                job1
            },
        )
        assertFalse(gate.tryStart { _, _ -> Job() })

        val cancelled = gate.cancel()
        assertEquals(job1, cancelled)
        assertTrue(job1.isCancelled)

        val job2 = CompletableDeferred<Unit>()
        assertTrue(
            gate.tryStart { gen, prev ->
                gen2 = gen
                seenPrevJob = prev
                job2
            },
        )
        assertEquals(job1, seenPrevJob)
        assertNotEquals(gen1, gen2)

        // 旧任务晚到清理不能误清新一代的运行状态
        assertFalse(gate.finish(gen1))
        assertFalse(gate.tryStart { _, _ -> Job() })

        assertTrue(gate.finish(gen2))
        assertTrue(gate.tryStart { _, prev ->
            assertNull(prev)
            Job()
        })
    }
}
