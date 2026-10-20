package com.andsi.airlyrics.app.interaction

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsDraftStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private fun store() = LyricsDraftStore(folder.root, executor)

    @org.junit.After fun stopWorkers() {
        executor.shutdownNow()
        assertTrue("Draft worker did not stop", executor.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test fun longDraftRoundTripsAndDeleteWinsOverQueuedWrites() {
        val store = store()
        val text = "[00:01.00]一行歌词\n".repeat(100000)
        store.write("draft", text)
        assertEquals(text, store.read("draft").get(5, TimeUnit.SECONDS))
        store.write("draft", "changed")
        store.delete("draft").get(5, TimeUnit.SECONDS)
        assertNull(store.read("draft").get(5, TimeUnit.SECONDS))
    }

    @Test fun readerContentPreservesQueuedWriteDeleteOrdering() = runBlocking {
        withTimeout(5.seconds) {
            val content = DraftReaderContent(store())
            content.save("reader", "first")
            content.save("reader", "latest")
            assertEquals("latest", content.load("reader"))
            content.save("reader", "queued")
            content.discard("reader")
            assertNull(content.load("reader"))
        }
    }

    @Test(timeout = 5_000) fun cancelledReaderDoesNotWaitForAQueuedDraftRead() = runBlocking {
        val releaseWorker = java.util.concurrent.CountDownLatch(1)
        val workerEntered = java.util.concurrent.CountDownLatch(1)
        val blockedWorker = executor.submit {
            workerEntered.countDown()
            check(releaseWorker.await(4, TimeUnit.SECONDS)) { "Draft test release gate timed out" }
        }
        assertTrue(workerEntered.await(1, TimeUnit.SECONDS))
        try {
            val failure = runCatching { withTimeout(100.milliseconds) { DraftReaderContent(store()).load("queued") } }.exceptionOrNull()
            assertTrue("Reader must remain cancellable while its Future is pending", failure is kotlinx.coroutines.TimeoutCancellationException)
            assertFalse("Timeout must return before the queued I/O finishes", blockedWorker.isDone)
        } finally {
            releaseWorker.countDown()
        }
    }

    @Test fun corruptDraftFallsBackAndPruningKeepsOnlyActiveDraft() {
        val store = store()
        store.write("active", "keep").get(5, TimeUnit.SECONDS)
        store.write("orphan", "discard").get(5, TimeUnit.SECONDS)
        store.prune("active").get(5, TimeUnit.SECONDS)
        assertEquals("keep", store.read("active").get(5, TimeUnit.SECONDS))
        assertNull(store.read("orphan").get(5, TimeUnit.SECONDS))
        File(folder.root, "active.json").writeText("broken json")
        assertNull(store.read("active").get(5, TimeUnit.SECONDS))
    }
}
