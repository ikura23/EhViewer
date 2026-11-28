package com.hippo.ehviewer.ui.reader

import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.PageStatus
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPageWaitersTest {
    @Test
    fun awaitPageTerminalWakesImmediatelyAndCancelsRetryJob() = runBlocking {
        val page = Page(0)
        val retryCalls = AtomicInteger(0)
        val start = System.currentTimeMillis()
        launch {
            delay(30)
            page.statusFlow.value = PageStatus.Error("err")
        }
        val status = awaitPageTerminal(
            page = page,
            timeoutMillis = 5_000L,
            onPeriodicRetry = { retryCalls.incrementAndGet() },
            retryIntervalMillis = 1_000L,
        )
        val elapsed = System.currentTimeMillis() - start
        assertTrue(status is PageStatus.Error)
        assertTrue("Expected fast wake-up, elapsed=${elapsed}ms", elapsed < 500L)
        assertEquals(0, retryCalls.get())
    }

    @Test
    fun awaitPageTerminalReturnsNullOnTimeout() = runBlocking {
        val page = Page(0)
        val retryCalls = AtomicInteger(0)
        val status = awaitPageTerminal(
            page = page,
            timeoutMillis = 120L,
            onPeriodicRetry = { retryCalls.incrementAndGet() },
            retryIntervalMillis = 40L,
        )
        assertNull(status)
        assertTrue(retryCalls.get() >= 1)
    }

    @Test
    fun awaitPageReadyWhileOnPageStopsWhenPageTurnsOrErrors() = runBlocking {
        val page0 = Page(0)
        val currentPage = MutableStateFlow(0)
        launch {
            delay(25)
            currentPage.value = 1
        }
        assertFalse(awaitPageReadyWhileOnPage(page0, currentPage, pageIndex = 0, timeoutMillis = 5_000L))

        val page1 = Page(1)
        val stayOn1 = MutableStateFlow(1)
        launch {
            delay(25)
            page1.statusFlow.value = PageStatus.Error("failed")
        }
        assertFalse(awaitPageReadyWhileOnPage(page1, stayOn1, pageIndex = 1, timeoutMillis = 5_000L))
        assertFalse(awaitPageReadyWhileOnPage(Page(2), MutableStateFlow(2), pageIndex = 2, timeoutMillis = 50L))
    }

    @Test
    fun sampleAnimatedPagesStopsEarlyWhenNonAnimatedExceedsHalf() = runBlocking {
        val pages = List(5) { Page(it) }
        val start = System.currentTimeMillis()
        launch {
            delay(20)
            pages[0].statusFlow.value = PageStatus.Error("non-anim")
            pages[1].statusFlow.value = PageStatus.Error("non-anim")
            pages[2].statusFlow.value = PageStatus.Error("non-anim")
        }
        val detected = sampleAnimatedPages(pages, 0..4, timeoutMillis = 5_000L)
        val elapsed = System.currentTimeMillis() - start
        assertFalse(detected)
        assertTrue("Expected early exit, elapsed=${elapsed}ms", elapsed < 500L)
    }
}
