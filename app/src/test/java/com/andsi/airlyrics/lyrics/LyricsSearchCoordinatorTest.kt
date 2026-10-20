package com.andsi.airlyrics.lyrics

import com.andsi.airlyrics.core.model.LyricsSearchMode
import com.andsi.airlyrics.core.model.SongIdentity
import java.util.concurrent.CancellationException
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
        val waiting = CountDownLatch(1)
        val acquired = CountDownLatch(1)
        try {
            val future = executor.submit {
                LyricsSearchCoordinator.begin(song, token("automatic"), LyricsSearchOrigin.Automatic, LyricsSearchMode.Original, waiting::countDown).use {
                    acquired.countDown()
                }
            }
            assertTrue("Automatic search never reached the ownership wait", waiting.await(5, TimeUnit.SECONDS))
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
        val waiting = CountDownLatch(1)
        try {
            val future = executor.submit<Boolean> {
                try {
                    LyricsSearchCoordinator.begin(song, automaticToken, LyricsSearchOrigin.Automatic, LyricsSearchMode.Original, waiting::countDown).close()
                    false
                } catch (_: CancellationException) {
                    true
                }
            }
            assertTrue("Automatic search never reached the ownership wait", waiting.await(5, TimeUnit.SECONDS))
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

    private fun token(key: String) = LyricsLookupCancellationToken(key, 1L)
}
