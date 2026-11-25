package com.hippo.ehviewer.download

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
}
