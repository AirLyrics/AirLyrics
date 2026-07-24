package com.andsi.airlyrics.lyrics

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsLookupRunnerTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val runner = LyricsLookupRunner(
        threadNamePrefix = "LyricsLookupRunnerTest",
        callbackDispatcher = { block -> block() },
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
        val firstWasCanceled = AtomicBoolean(false)
        val callbacks = Collections.synchronizedList(mutableListOf<String>())

        runner.submit(
            requestKey = "first",
            lookup = { token ->
                firstStarted.countDown()
                awaitEvenIfInterrupted(firstCanReturn)
                firstWasCanceled.set(token.isCancellationRequested)
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
        assertTrue(firstWasCanceled.get())
        assertEquals(listOf("second:new"), callbacks.toList())
    }

    @Test
    fun submit_cancelsQueuedLookupBeforeItStarts() {
        val productionExecutor = createLyricsLookupExecutor("LyricsLookupRunnerQueueTest")
        val productionRunner = LyricsLookupRunner(
            threadNamePrefix = "unused-by-injected-executor",
            callbackDispatcher = { block -> block() },
            executor = productionExecutor
        )
        val blockersCanReturn = CountDownLatch(1)
        val blockerStarted = List(productionExecutor.maximumPoolSize) { CountDownLatch(1) }
        val latestCallback = CountDownLatch(1)
        val queuedLookupRan = AtomicBoolean(false)
        val callbacks = Collections.synchronizedList(mutableListOf<String>())

        try {
            blockerStarted.indices.forEach { index ->
                productionRunner.submit(
                    requestKey = "blocker-$index",
                    lookup = {
                        blockerStarted[index].countDown()
                        awaitEvenIfInterrupted(blockersCanReturn)
                        Result.success("blocked-$index")
                    },
                    callback = { key, result -> callbacks += "$key:${result.getOrNull()}" }
                )
                assertTrue(blockerStarted[index].await(2, TimeUnit.SECONDS))
            }

            productionRunner.submit(
                requestKey = "queued",
                lookup = {
                    queuedLookupRan.set(true)
                    Result.success("queued")
                },
                callback = { key, result -> callbacks += "$key:${result.getOrNull()}" }
            )
            assertEquals(1, productionExecutor.queue.size)
            val queuedTask = productionExecutor.queue.single()
            val queuedFuture = queuedTask as Future<*>
            assertFalse(queuedFuture.isCancelled)

            productionRunner.submit(
                requestKey = "latest",
                lookup = { token ->
                    token.throwIfCancellationRequested()
                    Result.success("latest")
                },
                callback = { key, result ->
                    callbacks += "$key:${result.getOrNull()}"
                    latestCallback.countDown()
                }
            )

            assertTrue(queuedFuture.isCancelled)
            assertFalse(queuedTask in productionExecutor.queue)
            assertEquals(1, productionExecutor.queue.size)

            blockersCanReturn.countDown()

            assertTrue(latestCallback.await(2, TimeUnit.SECONDS))
            assertFalse(queuedLookupRan.get())
            assertEquals(listOf("latest:latest"), callbacks.toList())
        } finally {
            blockersCanReturn.countDown()
            productionRunner.shutdown()
        }
    }

    @Test
    fun productionExecutor_discardOldestPolicyDropsQueuedTaskWithoutCancellingIt() {
        val productionExecutor = createLyricsLookupExecutor("LyricsLookupRunnerPolicyTest")
        val blockersCanReturn = CountDownLatch(1)
        val blockersStarted = CountDownLatch(productionExecutor.maximumPoolSize)
        val discardedTaskRan = AtomicBoolean(false)
        val replacementTaskRan = CountDownLatch(1)
        val discardedTask = FutureTask {
            discardedTaskRan.set(true)
        }
        val replacementTask = FutureTask {
            replacementTaskRan.countDown()
        }

        try {
            assertEquals(3, productionExecutor.corePoolSize)
            assertEquals(3, productionExecutor.maximumPoolSize)
            assertEquals(
                1,
                productionExecutor.queue.size + productionExecutor.queue.remainingCapacity()
            )
            assertTrue(
                productionExecutor.rejectedExecutionHandler is
                    ThreadPoolExecutor.DiscardOldestPolicy
            )

            repeat(productionExecutor.maximumPoolSize) {
                productionExecutor.execute {
                    blockersStarted.countDown()
                    awaitEvenIfInterrupted(blockersCanReturn)
                }
            }
            assertTrue(blockersStarted.await(2, TimeUnit.SECONDS))

            productionExecutor.execute(discardedTask)
            assertTrue(discardedTask in productionExecutor.queue)

            productionExecutor.execute(replacementTask)

            assertFalse(discardedTask.isCancelled)
            assertFalse(discardedTask.isDone)
            assertFalse(discardedTask in productionExecutor.queue)
            assertTrue(replacementTask in productionExecutor.queue)

            blockersCanReturn.countDown()

            assertTrue(replacementTaskRan.await(2, TimeUnit.SECONDS))
            assertFalse(discardedTaskRan.get())
        } finally {
            blockersCanReturn.countDown()
            productionExecutor.shutdownNow()
        }
    }

    private fun awaitEvenIfInterrupted(latch: CountDownLatch) {
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (latch.count > 0 && System.nanoTime() < deadlineNanos) {
            try {
                latch.await(20, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                // A canceled lookup is expected to be interrupted. Keep this test lookup alive
                // until the test releases it so queued-request cancellation can be verified.
            }
        }
        assertEquals(0L, latch.count)
    }
}
