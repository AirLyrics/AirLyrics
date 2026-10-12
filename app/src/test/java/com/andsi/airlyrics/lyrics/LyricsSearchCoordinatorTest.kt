package com.andsi.airlyrics.lyrics

import com.andsi.airlyrics.core.model.LyricsSearchMode
import com.andsi.airlyrics.core.model.SongIdentity
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsSearchCoordinatorTest {
    @Test
    fun automaticSearchWaitsForManualSearchToClose() {
        val song = SongIdentity("waiting", "artist", "album", 1000L)
        val manual = LyricsSearchCoordinator.begin(song, token("manual"), LyricsSearchOrigin.Manual, LyricsSearchMode.Original)
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val acquired = CountDownLatch(1)
        try {
            val future = executor.submit {
                started.countDown()
                LyricsSearchCoordinator.begin(song, token("automatic"), LyricsSearchOrigin.Automatic, LyricsSearchMode.Original).use {
                    acquired.countDown()
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertFalse(acquired.await(200, TimeUnit.MILLISECONDS))
            manual.close()
            future.get(5, TimeUnit.SECONDS)
            assertTrue(acquired.await(5, TimeUnit.SECONDS))
        } finally {
            manual.close()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun waitingAutomaticSearchCanBeCancelledBeforeManualSearchCloses() {
        val song = SongIdentity("cancellation", "artist", "album", 1000L)
        val manual = LyricsSearchCoordinator.begin(song, token("manual"), LyricsSearchOrigin.Manual, LyricsSearchMode.Original)
        val automaticToken = token("automatic")
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        try {
            val future = executor.submit<Boolean> {
                started.countDown()
                try {
                    LyricsSearchCoordinator.begin(song, automaticToken, LyricsSearchOrigin.Automatic, LyricsSearchMode.Original).close()
                    false
                } catch (_: CancellationException) {
                    true
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            automaticToken.cancel()
            assertTrue(future.get(5, TimeUnit.SECONDS))
        } finally {
            manual.close()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun token(key: String) = LyricsLookupCancellationToken(key, 1L)
}
