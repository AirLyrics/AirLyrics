package com.andsi.airlyrics.lyrics

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsLookupRunnerTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val runner = LyricsLookupRunner(
        threadNamePrefix = "LyricsLookupRunnerTest",
        callbackDispatcher = LyricsLookupCallbackDispatcher { block -> block() },
        executor = executor
    )

    @After
    fun tearDown() {
        runner.shutdown()
    }

    @Test
    fun submit_replacesRunningLookupAndDeliversOnlyLatestResult() {
        val firstStarted = CountDownLatch(1)
        val firstCanReturn = CountDownLatch(1)
        val secondCallback = CountDownLatch(1)
        val firstWasCancelled = AtomicBoolean(false)
        val callbacks = Collections.synchronizedList(mutableListOf<String>())

        runner.submit(
            requestKey = "first",
            lookup = { token ->
                firstStarted.countDown()
                awaitEvenIfInterrupted(firstCanReturn)
                firstWasCancelled.set(token.isCancellationRequested)
                Result.success("old")
            },
            callback = { key, result -> callbacks += "$key:${result.getOrNull()}" }
        )

        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))

        runner.submit(
            requestKey = "second",
            lookup = { token ->
                token.throwIfCancellationRequested()
                Result.success("new")
            },
            callback = { key, result ->
                callbacks += "$key:${result.getOrNull()}"
                secondCallback.countDown()
            }
        )

        firstCanReturn.countDown()

        assertTrue(secondCallback.await(2, TimeUnit.SECONDS))
        assertTrue(firstWasCancelled.get())
        assertEquals(listOf("second:new"), callbacks.toList())
    }

    @Test
    fun submit_cancelsQueuedLookupBeforeItStarts() {
        val firstStarted = CountDownLatch(1)
        val firstCanReturn = CountDownLatch(1)
        val thirdCallback = CountDownLatch(1)
        val secondRan = AtomicBoolean(false)
        val callbacks = Collections.synchronizedList(mutableListOf<String>())

        runner.submit(
            requestKey = "first",
            lookup = {
                firstStarted.countDown()
                awaitEvenIfInterrupted(firstCanReturn)
                Result.success("old")
            },
            callback = { key, result -> callbacks += "$key:${result.getOrNull()}" }
        )

        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))

        runner.submit(
            requestKey = "second",
            lookup = {
                secondRan.set(true)
                Result.success("queued")
            },
            callback = { key, result -> callbacks += "$key:${result.getOrNull()}" }
        )

        runner.submit(
            requestKey = "third",
            lookup = { token ->
                token.throwIfCancellationRequested()
                Result.success("latest")
            },
            callback = { key, result ->
                callbacks += "$key:${result.getOrNull()}"
                thirdCallback.countDown()
            }
        )

        firstCanReturn.countDown()

        assertTrue(thirdCallback.await(2, TimeUnit.SECONDS))
        assertTrue(!secondRan.get())
        assertEquals(listOf("third:latest"), callbacks.toList())
    }

    private fun awaitEvenIfInterrupted(latch: CountDownLatch) {
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (latch.count > 0 && System.nanoTime() < deadlineNanos) {
            try {
                latch.await(20, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                // A cancelled lookup is expected to be interrupted. Keep this test lookup alive
                // until the test releases it so queued-request cancellation can be verified.
            }
        }
        assertEquals(0L, latch.count)
    }
}
