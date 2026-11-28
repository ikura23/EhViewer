package com.hippo.ehviewer.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRecoveryTest {
    @Test
    fun onlyInterruptedIdsResumeAndDuplicatesCannotRunTwice() {
        val recovery = DownloadRecovery(listOf(1, 2, 1))
        recovery.add(listOf(2, 3))
        assertEquals(listOf(1L, 2L, 3L), recovery.takePending())
        assertFalse(recovery.hasPending)
        assertEquals(emptyList<Long>(), recovery.takePending())
    }

    @Test
    fun timeoutOrStartDenialPreservesIntentWithoutRetrying() {
        var saved = DownloadRecovery.RetryState()
        val recovery = DownloadRecovery(listOf(1), saveRetry = { saved = it })
        recovery.deferUntilForeground()
        assertNull(recovery.nextDelayMillis())
        assertTrue(saved.waitingForForeground)
        val restored = DownloadRecovery(listOf(1), saved)
        assertNull(restored.nextDelayMillis(immediate = true))
        restored.onForeground()
        assertEquals(0L, restored.nextDelayMillis(immediate = true))
        assertEquals(listOf(1L), restored.takePending())
    }

    @Test
    fun foregroundArrivingBeforeDeferredCleanupDoesNotBlockRecovery() {
        val recovery = DownloadRecovery(listOf(1))
        recovery.onForeground()
        recovery.nextDelayMillis(immediate = true)
        recovery.deferUntilForeground(appInForeground = true)
        assertEquals(2000L, recovery.nextDelayMillis())
        recovery.deferUntilForeground(appInForeground = false)
        assertNull(recovery.nextDelayMillis())
    }

    @Test
    fun retryBudgetIsBoundedAcrossProcessRecreation() {
        var saved = DownloadRecovery.RetryState()
        val delays = (1..5).map {
            DownloadRecovery(listOf(1), saved, { saved = it }).nextDelayMillis()
        }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L), delays)
        val exhausted = DownloadRecovery(listOf(1), saved)
        assertNull(exhausted.nextDelayMillis())
        assertEquals(listOf(1L), exhausted.takePending())
    }

    @Test
    fun successfulHandoffDoesNotResetTheCrashLoopBudget() {
        val recovery = DownloadRecovery(listOf(1))
        repeat(5) {
            assertEquals(1000L shl it, recovery.nextDelayMillis())
            val active = recovery.takePending()
            recovery.add(active)
        }
        assertNull(recovery.nextDelayMillis())
        recovery.onForeground()
        assertEquals(1000L, recovery.nextDelayMillis())
    }

    @Test
    fun stopOrDeleteAfterSchedulingRemovesOnlyTheSelectedIntent() {
        val recovery = DownloadRecovery(listOf(1, 2))
        assertEquals(1000L, recovery.nextDelayMillis())
        assertTrue(recovery.remove(1))
        assertFalse(recovery.contains(1))
        assertFalse(recovery.remove(1))
        assertEquals(listOf(2L), recovery.takePending())
        assertNull(recovery.nextDelayMillis())
    }

    @Test
    fun stopAllDuringBackoffLeavesNothingForALateTimer() {
        val recovery = DownloadRecovery(listOf(1, 2))
        recovery.nextDelayMillis()
        recovery.takePending()
        assertFalse(recovery.hasPending)
        assertNull(recovery.nextDelayMillis())
        assertEquals(emptyList<Long>(), recovery.takePending())
    }

    @Test
    fun clearWaitingForForegroundPreservesRetryBudget() {
        var saved = DownloadRecovery.RetryState()
        val recovery = DownloadRecovery(listOf(1), saveRetry = { saved = it })
        assertEquals(1000L, recovery.nextDelayMillis())
        recovery.deferUntilForeground()
        assertTrue(saved.waitingForForeground)
        assertEquals(1, saved.attempts)
        assertNull(recovery.nextDelayMillis())

        recovery.clearWaitingForForeground()
        assertFalse(saved.waitingForForeground)
        assertEquals(1, saved.attempts)
        assertEquals(2000L, recovery.nextDelayMillis())
    }

    @Test
    fun stopAndRecoveryUseTheLiveSetOnTheSameCommandOwner() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val owner = DownloadCommands(scope) { throw AssertionError(it) }
            val recovery = DownloadRecovery(listOf(1, 2))
            owner.post { recovery.remove(1) }
            val resumed = owner.execute { recovery.takePending() }
            assertEquals(listOf(2L), resumed)
            val active = resumed.toMutableSet()
            owner.post { active.remove(2) }
            assertEquals(emptySet<Long>(), owner.execute { active.toSet() })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun commandsCancelPropagatesWithoutCallingReportFailureAndCancelsEnqueue() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val reported = mutableListOf<Throwable>()
        try {
            val owner = DownloadCommands(scope) { reported += it }
            owner.post { throw CancellationException("post cancelled") }
            val deferred = owner.enqueue<String> { throw CancellationException("enqueue cancelled") }
            deferred.join()
            assertTrue(deferred.isCancelled)
            assertEquals("ok", owner.execute { "ok" })
            assertTrue(reported.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun commandsNormalFailureReportsAndContinuesProcessing() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val reported = mutableListOf<Throwable>()
        try {
            val owner = DownloadCommands(scope) { reported += it }
            owner.post { throw IllegalStateException("post boom") }
            val failed = runCatching { owner.execute<String> { error("exec boom") } }
            assertTrue(failed.isFailure)
            assertEquals("still-alive", owner.execute { "still-alive" })
            assertEquals(1, reported.size)
            assertTrue(reported[0] is IllegalStateException)
        } finally {
            scope.cancel()
        }
    }
}
