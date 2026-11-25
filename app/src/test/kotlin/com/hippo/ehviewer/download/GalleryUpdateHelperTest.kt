package com.hippo.ehviewer.download

import com.ehviewer.core.model.BaseGalleryInfo
import com.hippo.ehviewer.spider.SpiderInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryUpdateHelperTest {
    @Test
    fun mapsAppendedPagesAndKeepsExistingIndices() {
        val oldTokens = mapOf(0 to "aaa", 1 to "bbb", 2 to "ccc")
        val newTokens = mapOf(0 to "aaa", 1 to "bbb", 2 to "ccc", 3 to "ddd", 4 to "eee")
        val mapping = computeReusablePageMapping(oldTokens, newTokens)
        assertEquals(mapOf(0 to 0, 1 to 1, 2 to 2), mapping)
    }

    @Test
    fun mapsInsertedAndDeletedPagesByPToken() {
        val oldTokens = mapOf(0 to "p1", 1 to "p2", 2 to "p3", 3 to "p4")
        // 删除 p2，在开头插新页 x0，并在 p3 后插入 x1
        val newTokens = mapOf(0 to "x0", 1 to "p1", 2 to "p3", 3 to "x1", 4 to "p4")
        val mapping = computeReusablePageMapping(oldTokens, newTokens)
        assertEquals(mapOf(1 to 0, 2 to 2, 4 to 3), mapping)
    }

    @Test
    fun ignoresBlankTokensAndMapsDuplicateTokensToFirstOldPage() {
        val oldTokens = mapOf(0 to "", 1 to "dup", 2 to "dup", 3 to "unique")
        val newTokens = mapOf(0 to "", 1 to "dup", 2 to "unique", 3 to "dup")
        val mapping = computeReusablePageMapping(oldTokens, newTokens)
        assertEquals(mapOf(1 to 1, 2 to 3, 3 to 1), mapping)
    }

    @Test
    fun returnsEmptyMappingWhenAllPagesReplaced() {
        val oldTokens = mapOf(0 to "old1", 1 to "old2")
        val newTokens = mapOf(0 to "new1", 1 to "new2")
        assertEquals(emptyMap<Int, Int>(), computeReusablePageMapping(oldTokens, newTokens))
    }

    @Test
    fun parsesParentGidFromEhAndExUrls() {
        assertEquals(1234567L, parseParentGid("https://e-hentai.org/g/1234567/abcdef0123/"))
        assertEquals(7654321L, parseParentGid("https://exhentai.org/g/7654321/0123abcdef/"))
        assertNull(parseParentGid(null))
        assertNull(parseParentGid("https://e-hentai.org/"))
    }

    @Test
    fun extractsZeroBasedPageIndexFromFileNames() {
        assertEquals(0, extractIndexFromEntryName("00000001.jpg"))
        assertEquals(41, extractIndexFromEntryName("sub/00000042.webp"))
        assertEquals(999, extractIndexFromEntryName("1000.png"))
        assertNull(extractIndexFromEntryName("ComicInfo.xml"))
        assertNull(extractIndexFromEntryName(".ehviewer"))
        assertNull(extractIndexFromEntryName("00000000.jpg"))
    }

    @Test
    fun picksLatestNewerVersion() {
        val v1 = BaseGalleryInfo(gid = 100L, token = "t1", title = "V1")
        val v2 = BaseGalleryInfo(gid = 200L, token = "t2", title = "V2")
        assertNull(latestNewerVersion(emptyList()))
        assertEquals(200L, latestNewerVersion(listOf(v1, v2))?.gid)
    }

    @Test
    fun checksWhetherLocalSpiderInfoCoversRequiredOldPTokens() {
        assertTrue(hasAllRequiredOldPTokens(null, emptySet(), hasArchive = false))
        assertFalse(hasAllRequiredOldPTokens(null, setOf(0, 1), hasArchive = false))
        assertFalse(hasAllRequiredOldPTokens(null, emptySet(), hasArchive = true))

        val partial = SpiderInfo(
            gid = 100L,
            token = "abcdef0123",
            pages = 100,
            pTokenMap = hashMapOf(0 to "t0", 1 to "t1", 2 to ""),
        )
        assertTrue(hasAllRequiredOldPTokens(partial, setOf(0, 1), hasArchive = false))
        assertFalse(hasAllRequiredOldPTokens(partial, setOf(0, 2), hasArchive = false))
        assertFalse(hasAllRequiredOldPTokens(partial, setOf(0, 5), hasArchive = false))
        assertFalse(hasAllRequiredOldPTokens(partial, emptySet(), hasArchive = true))

        val fullArchive = SpiderInfo(
            gid = 101L,
            token = "abcdef0124",
            pages = 2,
            pTokenMap = hashMapOf(0 to "a", 1 to "b"),
        )
        assertTrue(hasAllRequiredOldPTokens(fullArchive, emptySet(), hasArchive = true))
    }
}
