package com.andsi.airlyrics.lyrics

import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingDeque
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
    fun submit_queueEvictionNeverLeavesRequestNonTerminal_andLatestWins() {
        val productionTemplate = createLyricsLookupExecutor("LyricsLookupRunnerPolicyTemplate")
        val productionExecutor = GatedProductionExecutor(productionTemplate)
        productionTemplate.shutdownNow()
        val productionRunner = LyricsLookupRunner(
            threadNamePrefix = "unused-by-injected-executor",
            callbackDispatcher = { block -> block() },
            executor = productionExecutor
        )
        val blockersCanReturn = CountDownLatch(1)
        val blockerStarted = List(productionExecutor.maximumPoolSize) { CountDownLatch(1) }
        val latestCallback = CountDownLatch(1)
        val callbacks = Collections.synchronizedList(mutableListOf<String>())
        val blockerHandles = mutableListOf<LyricsLookupHandle>()
        val submitters = Executors.newFixedThreadPool(2)

        try {
            blockerStarted.indices.forEach { index ->
                blockerHandles += productionRunner.submit(
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

            productionExecutor.gateNextSubmission()
            val staleSubmission = submitters.submit<LyricsLookupHandle> {
                productionRunner.submit(
                    requestKey = "stale-before-execute",
                    lookup = {
                        Result.success("stale")
                    },
                    callback = { key, result -> callbacks += "$key:${result.getOrNull()}" }
                )
            }
            assertTrue(productionExecutor.awaitSubmissionGated())

            val latestSubmissionReturned = CountDownLatch(1)
            val latestSubmission = submitters.submit<LyricsLookupHandle> {
                productionRunner.submit(
                    requestKey = "latest",
                    lookup = {
                        Result.success("latest")
                    },
                    callback = { key, result ->
                        callbacks += "$key:${result.getOrNull()}"
                        latestCallback.countDown()
                    }
                ).also {
                    latestSubmissionReturned.countDown()
                }
            }

            latestSubmissionReturned.await(2, TimeUnit.SECONDS)
            productionExecutor.releaseGatedSubmission()

            val staleHandle = staleSubmission.get(2, TimeUnit.SECONDS)
            val latestHandle = latestSubmission.get(2, TimeUnit.SECONDS)
            blockersCanReturn.countDown()

            val latestWasDelivered = latestCallback.await(2, TimeUnit.SECONDS)
            val terminalStates = (blockerHandles + staleHandle + latestHandle)
                .associate { handle ->
                    System.identityHashCode(handle) to
                        "done=${handle.isDone},cancelled=${handle.isCancelled}"
                }

            assertTrue(
                "Every runner request must terminate and the latest callback must be delivered; " +
                    "latestDelivered=$latestWasDelivered, states=$terminalStates, callbacks=$callbacks",
                latestWasDelivered &&
                    terminalStates.values.all { state -> state != "done=false,cancelled=false" }
            )
            assertEquals(listOf("latest:latest"), callbacks.toList())
        } finally {
            productionExecutor.releaseGatedSubmission()
            blockersCanReturn.countDown()
            submitters.shutdownNow()
            productionRunner.shutdown()
            assertTrue(submitters.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(productionExecutor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun shutdown_concurrentSubmit_leavesEveryHandleTerminal() {
        val productionTemplate = createLyricsLookupExecutor("LyricsLookupRunnerShutdownTemplate")
        val productionExecutor = GatedShutdownExecutor(productionTemplate)
        productionTemplate.shutdownNow()
        val productionRunner = LyricsLookupRunner(
            threadNamePrefix = "unused-by-injected-executor",
            callbackDispatcher = { block -> block() },
            executor = productionExecutor
        )
        val blockersCanReturn = CountDownLatch(1)
        val blockerStarted = List(productionExecutor.maximumPoolSize) { CountDownLatch(1) }
        val blockerHandles = mutableListOf<LyricsLookupHandle>()
        val callbacks = Collections.synchronizedList(mutableListOf<String>())
        val actors = Executors.newFixedThreadPool(2)

        try {
            blockerStarted.indices.forEach { index ->
                blockerHandles += productionRunner.submit(
                    requestKey = "shutdown-blocker-$index",
                    lookup = {
                        blockerStarted[index].countDown()
                        awaitEvenIfInterrupted(blockersCanReturn)
                        Result.success("blocked-$index")
                    },
                    callback = { key, _ -> callbacks += key }
                )
                assertTrue(blockerStarted[index].await(2, TimeUnit.SECONDS))
            }

            val shutdown = actors.submit<Unit> {
                productionRunner.shutdown()
            }
            assertTrue(productionExecutor.awaitShutdownNowEntered())

            val lateSubmissionReturned = CountDownLatch(1)
            val lateSubmission = actors.submit<LyricsLookupHandle> {
                productionRunner.submit(
                    requestKey = "after-shutdown-started",
                    lookup = { Result.success("must-not-run") },
                    callback = { key, _ -> callbacks += key }
                ).also {
                    lateSubmissionReturned.countDown()
                }
            }

            assertTrue(
                "submit after closed must return a terminal handle before shutdownNow completes",
                lateSubmissionReturned.await(2, TimeUnit.SECONDS)
            )
            productionExecutor.releaseShutdownNow()
            shutdown.get(2, TimeUnit.SECONDS)
            val lateHandle = lateSubmission.get(2, TimeUnit.SECONDS)
            blockersCanReturn.countDown()

            val allHandles = blockerHandles + lateHandle
            val terminalStates = allHandles.map { handle ->
                "done=${handle.isDone},cancelled=${handle.isCancelled}"
            }
            val returnedTaskStates = productionExecutor.returnedQueuedTasks.map { task ->
                val future = task as? Future<*>
                "done=${future?.isDone},cancelled=${future?.isCancelled}"
            }

            assertTrue(
                "shutdown must reject late admission and cancel every task returned by shutdownNow; " +
                    "handles=$terminalStates, returned=$returnedTaskStates, callbacks=$callbacks",
                terminalStates.none { it == "done=false,cancelled=false" } &&
                    returnedTaskStates.none { it == "done=false,cancelled=false" }
            )
            assertTrue(callbacks.isEmpty())
        } finally {
            productionExecutor.releaseShutdownNow()
            blockersCanReturn.countDown()
            actors.shutdownNow()
            productionRunner.shutdown()
            assertTrue(actors.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(productionExecutor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private class GatedProductionExecutor(
        template: ThreadPoolExecutor
    ) : ThreadPoolExecutor(
        template.corePoolSize,
        template.maximumPoolSize,
        template.getKeepAliveTime(TimeUnit.NANOSECONDS),
        TimeUnit.NANOSECONDS,
        LinkedBlockingDeque(
            template.queue.size + template.queue.remainingCapacity()
        ),
        template.threadFactory,
        template.rejectedExecutionHandler
    ) {
        private val gateArmed = AtomicBoolean(false)
        private val submissionGated = CountDownLatch(1)
        private val submissionReleased = CountDownLatch(1)

        fun gateNextSubmission() {
            assertTrue(gateArmed.compareAndSet(false, true))
        }

        fun awaitSubmissionGated(): Boolean = submissionGated.await(2, TimeUnit.SECONDS)

        fun releaseGatedSubmission() {
            submissionReleased.countDown()
        }

        override fun execute(command: Runnable) {
            if (gateArmed.compareAndSet(true, false)) {
                submissionGated.countDown()
                if (!submissionReleased.await(5, TimeUnit.SECONDS)) {
                    throw IllegalStateException("Timed out waiting to release executor submission")
                }
            }
            super.execute(command)
        }
    }

    private class GatedShutdownExecutor(
        template: ThreadPoolExecutor
    ) : ThreadPoolExecutor(
        template.corePoolSize,
        template.maximumPoolSize,
        template.getKeepAliveTime(TimeUnit.NANOSECONDS),
        TimeUnit.NANOSECONDS,
        LinkedBlockingDeque(
            template.queue.size + template.queue.remainingCapacity()
        ),
        template.threadFactory,
        template.rejectedExecutionHandler
    ) {
        private val shutdownNowEntered = CountDownLatch(1)
        private val shutdownNowReleased = CountDownLatch(1)
        val returnedQueuedTasks = CopyOnWriteArrayList<Runnable>()

        fun awaitShutdownNowEntered(): Boolean = shutdownNowEntered.await(2, TimeUnit.SECONDS)

        fun releaseShutdownNow() {
            shutdownNowReleased.countDown()
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdownNowEntered.countDown()
            if (!shutdownNowReleased.await(5, TimeUnit.SECONDS)) {
                throw IllegalStateException("Timed out waiting to release shutdownNow")
            }
            return super.shutdownNow().also(returnedQueuedTasks::addAll)
        }
    }

    private fun awaitEvenIfInterrupted(latch: CountDownLatch) {
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
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
