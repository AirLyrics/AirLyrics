package com.andsi.airlyrics.lyrics

import com.andsi.airlyrics.core.model.LyricsSearchMode
import com.andsi.airlyrics.core.model.SongIdentity
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsSearchCoordinatorTest {
    @Test
    fun automaticSearchWaitsForManualSearchToClose() {
        val song = SongIdentity("waiting", "artist", "album", 1000L)
        val manual = LyricsSearchCoordinator.begin(song, token("manual"), LyricsSearchOrigin.Manual, LyricsSearchMode.Original)
        val executor = Executors.newSingleThreadExecutor()
        val worker = AtomicReference<Thread>()
        val acquired = CountDownLatch(1)
        try {
            val future = executor.submit {
                worker.set(Thread.currentThread())
                LyricsSearchCoordinator.begin(song, token("automatic"), LyricsSearchOrigin.Automatic, LyricsSearchMode.Original).use {
                    acquired.countDown()
                }
            }
            awaitCoordinatorWait(worker)
            assertEquals(1L, acquired.count)
            manual.close()
            future.get(5, TimeUnit.SECONDS)
            assertTrue(acquired.await(5, TimeUnit.SECONDS))
        } finally {
            manual.close()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun waitingAutomaticSearchCanBeCancelledBeforeManualSearchCloses() {
        val song = SongIdentity("cancellation", "artist", "album", 1000L)
        val manual = LyricsSearchCoordinator.begin(song, token("manual"), LyricsSearchOrigin.Manual, LyricsSearchMode.Original)
        val automaticToken = token("automatic")
        val executor = Executors.newSingleThreadExecutor()
        val worker = AtomicReference<Thread>()
        try {
            val future = executor.submit<Boolean> {
                worker.set(Thread.currentThread())
                try {
                    LyricsSearchCoordinator.begin(song, automaticToken, LyricsSearchOrigin.Automatic, LyricsSearchMode.Original).close()
                    false
                } catch (_: CancellationException) {
                    true
                }
            }
            awaitCoordinatorWait(worker)
            automaticToken.cancel()
            assertTrue(future.get(5, TimeUnit.SECONDS))
            assertNull(LyricsSearchCoordinator.current(automaticToken))
            // Cancellation must finish while the manual owner is still active.
            assertEquals(LyricsSearchMode.Original, LyricsSearchCoordinator.manualMode(song))
        } finally {
            manual.close()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun awaitCoordinatorWait(worker: AtomicReference<Thread>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            // Thread stack inspection is available on both Android and JVM 17.
            // Observe the wait inside begin(), not merely that the worker started.
            val stack = worker.get()?.stackTrace.orEmpty()
            if (stack.any { it.className == LyricsSearchCoordinator::class.java.name && it.methodName == "begin" } &&
                stack.any { it.methodName.startsWith("await") }
            ) return
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
        }
        throw AssertionError("Automatic search never entered the coordinator's wait; worker=${worker.get()?.state}")
    }

    private fun token(key: String) = LyricsLookupCancellationToken(key, 1L)
}
