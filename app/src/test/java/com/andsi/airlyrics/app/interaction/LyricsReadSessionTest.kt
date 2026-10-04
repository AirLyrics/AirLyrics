package com.andsi.airlyrics.app.interaction

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LyricsReadSessionTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun media(title: String) = CurrentMediaInfo("player", title, "Artist", "Album", 1000L, isPlaying = false, 0L)
    private fun snapshot(request: LyricsReadRequest) = LyricsReadSnapshot(request.media, null, wordByWord = false, wordEnabled = false, 0L, null, emptyList())

    @Test fun recreationReusesThePendingReadAndCompletedRawSnapshot() = runTest {
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val session = LyricsReadSession(context, this, StandardTestDispatcher(testScheduler)) {
            reads++
            gate.await()
            snapshot(it)
        }
        val first = session.load(LyricsReadKind.CURRENT, media("A"))
        runCurrent()
        assertSame(first, session.load(LyricsReadKind.CURRENT, media("A")))
        gate.complete(Unit)
        runCurrent()
        assertSame(first, session.load(LyricsReadKind.CURRENT, media("A")))
        assertEquals(1, reads)
        assertTrue(first.await().isSuccess)
    }

    @Test fun lateNonCancellableReadCannotReplaceANewerSong() = runTest {
        val gate = CompletableDeferred<Unit>()
        val session = LyricsReadSession(context, this, StandardTestDispatcher(testScheduler)) {
            if (it.media?.title == "A") withContext(NonCancellable) { gate.await() }
            snapshot(it)
        }
        val old = session.load(LyricsReadKind.CURRENT, media("A"))
        runCurrent()
        val newer = session.load(LyricsReadKind.CURRENT, media("B"))
        runCurrent()
        assertEquals("B", newer.await().getOrThrow().media!!.title)
        gate.complete(Unit)
        runCurrent()
        assertTrue(old.isCancelled)
        assertSame(newer, session.load(LyricsReadKind.CURRENT, media("B")))
    }

    @Test fun failuresCanRetryAndDifferentOwnersDoNotCancelEachOther() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var fail = true
        val firstOwner = LyricsReadSession(context, this, dispatcher) {
            if (fail) error("temporary failure")
            snapshot(it)
        }
        val secondOwner = LyricsReadSession(context, this, dispatcher, ::snapshot)
        val failed = firstOwner.load(LyricsReadKind.SAVED, null)
        val independent = secondOwner.load(LyricsReadKind.SAVED, null)
        runCurrent()
        assertTrue(failed.await().isFailure)
        fail = false
        val retried = firstOwner.load(LyricsReadKind.SAVED, null)
        runCurrent()
        assertNotSame(failed, retried)
        assertTrue(retried.await().isSuccess)
        assertTrue(independent.await().isSuccess)
        assertFalse(independent.isCancelled)
        assertNotSame(retried, firstOwner.load(LyricsReadKind.SAVED, null, force = true))
    }
}
