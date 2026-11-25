package com.hippo.ehviewer.spider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpiderInfoTest {
    @Test
    fun reconcileExpandsStalePagesFromGalleryAndLocalProgress() {
        val info = SpiderInfo(
            gid = 4203956L,
            token = "200508ac63",
            pages = 39,
            pTokenMap = hashMapOf(0 to "93baceece8", 339 to "699e42148b"),
        )

        val changed = reconcileSpiderInfoPages(
            spiderInfo = info,
            galleryPages = 1102,
            localMaxPage = 331,
        )

        assertTrue(changed)
        assertEquals(1102, info.pages)
    }

    @Test
    fun reconcileFallsBackToPTokenAndLocalPageWhenGalleryPagesMissing() {
        val info = SpiderInfo(
            gid = 4203956L,
            token = "200508ac63",
            pages = 39,
            pTokenMap = hashMapOf(0 to "93baceece8", 339 to "699e42148b"),
        )

        val changed = reconcileSpiderInfoPages(
            spiderInfo = info,
            galleryPages = 0,
            localMaxPage = 331,
        )

        assertTrue(changed)
        assertEquals(340, info.pages)
    }

    @Test
    fun reconcilePrefersLocalMaxPageWhenLargest() {
        val info = SpiderInfo(
            gid = 100L,
            token = "abcdef1234",
            pages = 10,
        )

        val changed = reconcileSpiderInfoPages(
            spiderInfo = info,
            galleryPages = 12,
            localMaxPage = 45,
        )

        assertTrue(changed)
        assertEquals(45, info.pages)
    }

    @Test
    fun reconcileReturnsFalseWhenAlreadyUpToDate() {
        val info = SpiderInfo(
            gid = 100L,
            token = "abcdef1234",
            pages = 1102,
            pTokenMap = hashMapOf(339 to "699e42148b"),
        )

        val changed = reconcileSpiderInfoPages(
            spiderInfo = info,
            galleryPages = 1102,
            localMaxPage = 331,
        )

        assertFalse(changed)
        assertEquals(1102, info.pages)
    }
}
