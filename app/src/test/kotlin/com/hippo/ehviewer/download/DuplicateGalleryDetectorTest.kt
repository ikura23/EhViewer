package com.hippo.ehviewer.download

import com.ehviewer.core.database.model.DownloadEntity
import com.ehviewer.core.database.model.DownloadInfo
import com.ehviewer.core.database.model.GalleryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateGalleryDetectorTest {
    private fun fakeDownload(
        gid: Long,
        title: String,
        pages: Int,
        label: String? = null,
        time: Long = gid,
    ): DownloadInfo = DownloadInfo(
        galleryInfo = GalleryEntity(
            gid = gid,
            token = "0123456789",
            title = title,
            titleJpn = null,
            thumbKey = null,
            category = 0,
            posted = null,
            uploader = null,
            rating = 0f,
            simpleTags = null,
            pages = pages,
            simpleLanguage = null,
            favoriteSlot = -2,
        ),
        dirname = "$gid-test",
        downloadInfo = DownloadEntity(gid = gid, time = time, label = label),
    )

    @Test
    fun normalizesTitlesWithLeadingGidAndWhitespaceOnly() {
        assertEquals(
            "[dydy_cos] animated collection (webp) [ai generated]",
            normalizeGalleryTitleForDuplicateCheck("  4203956-[DyDy_cos]   Animated Collection (WEBP) [AI Generated] "),
        )
        assertEquals(
            "[case]kagari_fuyukawa冬川花璃_冬川カガリ_合集2[ai generated]",
            normalizeGalleryTitleForDuplicateCheck("4082229-[case]kagari_fuyukawa冬川花璃_冬川カガリ_合集2[AI Generated]"),
        )
    }

    @Test
    fun detectsSameNameOldAndNewVersionsWithoutConflatingSequels() {
        val oldVer = fakeDownload(4203956L, "[DyDy_cos] Animated Collection (WEBP) [AI Generated]", pages = 60, time = 100L)
        val newVer = fakeDownload(4343028L, "[DyDy_cos] Animated Collection (WEBP) [AI Generated]", pages = 935, time = 200L)
        val part1 = fakeDownload(4061811L, "[case]kagari_fuyukawa冬川花璃_冬川カガリ_合集[AI Generated]", pages = 322, time = 150L)
        val part2 = fakeDownload(4082229L, "[case]kagari_fuyukawa冬川花璃_冬川カガリ_合集2[AI Generated]", pages = 74, time = 160L)
        val col4 = fakeDownload(3838201L, "My realistic AI animated collection 4 [ai generated]", pages = 367, time = 170L)
        val col5 = fakeDownload(3873133L, "My realistic AI animated collection 5 [ai generated]", pages = 420, time = 180L)

        val groups = findDuplicateGalleryGroups(
            items = listOf(oldVer, newVer, part1, part2, col4, col5),
            titleSelector = { it.title.orEmpty() },
        )

        assertEquals(1, groups.size)
        val group = groups.single()
        assertEquals(4343028L, group.newest.gid)
        assertEquals(listOf(4203956L), group.older.map { it.gid })
    }

    @Test
    fun respectsIgnoredKeysAndPrefersRemotePrimaryDownloads() {
        val localArchive = fakeDownload(-12345L, "Same Gallery", pages = 100, label = "本地", time = 300L)
        val remotePrimary = fakeDownload(4000001L, "Same Gallery", pages = 80, label = null, time = 200L)

        val detected = findDuplicateGalleryGroups(
            items = listOf(localArchive, remotePrimary),
            titleSelector = { it.title.orEmpty() },
        )
        assertEquals(1, detected.size)
        assertEquals(4000001L, detected.single().newest.gid)
        assertEquals(listOf(-12345L), detected.single().older.map { it.gid })

        val filtered = findDuplicateGalleryGroups(
            items = listOf(localArchive, remotePrimary),
            ignoredKeys = setOf(detected.single().key),
            titleSelector = { it.title.orEmpty() },
        )
        assertTrue(filtered.isEmpty())
    }
}
