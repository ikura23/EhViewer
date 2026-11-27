package com.hippo.ehviewer.ui.experimental

import com.ehviewer.core.model.BaseGalleryInfo
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.client.data.ListUrlBuilder.Companion.MODE_NORMAL
import com.hippo.ehviewer.ui.main.AdvanceTable
import com.hippo.ehviewer.ui.screen.excludePinnedGallery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalUiHelpersTest {

    @Test
    fun `ExperimentalSearchFilterState builds advance mask and builder accurately`() {
        val initial = ExperimentalSearchFilterState()
        assertEquals(0, initial.activeCount())
        assertEquals(0, initial.toAdvanceSearchMask())

        val configured = initial
            .toggleCategory(EhUtils.DOUJINSHI)
            .toggleCategory(EhUtils.MANGA)
            .copy(
                languageIndex = 1,
                minRating = 4,
                fromPage = 20,
                toPage = 60,
                onlyAnimated = true,
                onlyTorrents = true,
                disableLangFilter = true,
            )

        assertTrue(configured.isCategoryChecked(EhUtils.DOUJINSHI))
        assertTrue(configured.isCategoryChecked(EhUtils.MANGA))
        assertEquals(EhUtils.DOUJINSHI or EhUtils.MANGA, configured.categoryMask)
        assertEquals(AdvanceTable.STO or AdvanceTable.SFL, configured.toAdvanceSearchMask())
        assertEquals(7, configured.activeCount())

        val builder = configured.buildListUrlBuilder("blue archive")
        assertEquals(MODE_NORMAL, builder.mode)
        assertEquals(EhUtils.DOUJINSHI or EhUtils.MANGA, builder.category)
        assertEquals(1, builder.language)
        assertEquals(4, builder.minRating)
        assertEquals(20, builder.pageFrom)
        assertEquals(60, builder.pageTo)
        assertEquals(AdvanceTable.STO or AdvanceTable.SFL, builder.advanceSearch)
        assertEquals("blue archive other:animated$", builder.keyword)
    }

    @Test
    fun `shouldShowExperimentalBottomBar returns true only when enabled and on top-level route`() {
        assertFalse(shouldShowExperimentalBottomBar(enabled = false, currentRoute = "home_page_screen"))
        assertTrue(shouldShowExperimentalBottomBar(enabled = true, currentRoute = "home_page_screen"))
        assertTrue(shouldShowExperimentalBottomBar(enabled = true, currentRoute = "experimental_search_screen"))
        assertTrue(shouldShowExperimentalBottomBar(enabled = true, currentRoute = "downloads_screen"))
        assertFalse(shouldShowExperimentalBottomBar(enabled = true, currentRoute = "gallery_detail_screen/123"))
        assertFalse(shouldShowExperimentalBottomBar(enabled = true, currentRoute = "reader_screen"))
        assertFalse(shouldShowExperimentalBottomBar(enabled = true, currentRoute = null))
    }

    @Test
    fun `excludePinnedGallery removes matching gid while preserving order when pinnedGid is null`() {
        val g1 = BaseGalleryInfo(gid = 10L, token = "a")
        val g2 = BaseGalleryInfo(gid = 20L, token = "b")
        val g3 = BaseGalleryInfo(gid = 30L, token = "c")
        val source = listOf(g1, g2, g3)

        assertEquals(listOf(10L, 20L, 30L), excludePinnedGallery(source, null).map { it.gid })
        assertEquals(listOf(10L, 30L), excludePinnedGallery(source, 20L).map { it.gid })
    }
}
