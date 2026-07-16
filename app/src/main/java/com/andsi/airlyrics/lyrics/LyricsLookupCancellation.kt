package com.andsi.airlyrics.lyrics

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Cooperative cancellation token for lyrics lookups.
 *
 * Canceling a lookup marks the token and interrupts the worker. Kotlin/file based lookup stages
 * check the token between steps, while Rust/JNI provider calls receive a native lookup id and can
 * stop between network stages. Results from canceled tokens are never delivered back to the UI.
 */
class LyricsLookupCancellationToken internal constructor(
    val requestKey: String,
    val generation: Long,
    val nativeLookupId: Long = nextNativeLookupId()
) {
    private val lock = Any()
    private val cancellationCallbacks = mutableListOf<() -> Unit>()

    @Volatile
    var isCancellationRequested: Boolean = false
        private set

    internal fun cancel() {
        val callbacks = synchronized(lock) {
            if (isCancellationRequested) {
                emptyList()
            } else {
                isCancellationRequested = true
                cancellationCallbacks.toList()
            }
        }
        callbacks.forEach { callback ->
            runCatching { callback() }
        }
    }

    fun throwIfCancellationRequested() {
        if (isCancellationRequested || Thread.currentThread().isInterrupted) {
            throw CancellationException("Lyrics lookup was canceled: $requestKey#$generation")
        }
    }

    internal fun invokeOnCancellation(callback: () -> Unit): LyricsLookupCancellationRegistration {
        var runImmediately = false
        synchronized(lock) {
            if (isCancellationRequested) {
                runImmediately = true
            } else {
                cancellationCallbacks += callback
            }
        }

        if (runImmediately) {
            runCatching { callback() }
        }

        return LyricsLookupCancellationRegistration {
            synchronized(lock) {
                cancellationCallbacks.remove(callback)
            }
        }
    }

    companion object {
        private val nativeLookupIdCounter = AtomicLong(1L)

        private fun nextNativeLookupId(): Long = nativeLookupIdCounter.getAndIncrement()
    }
}

internal class LyricsLookupCancellationRegistration(
    private val disposeAction: () -> Unit
) {
    fun dispose() {
        disposeAction()
    }
}

class LyricsLookupHandle internal constructor(
    private val token: LyricsLookupCancellationToken,
    private val future: Future<*>
) {
    fun cancel() {
        token.cancel()
        future.cancel(true)
    }
}

fun interface LyricsLookupCallbackDispatcher {
    fun dispatch(block: () -> Unit)
}

private const val DEFAULT_MAX_PARALLEL_LOOKUPS = 3
private const val DEFAULT_LOOKUP_QUEUE_CAPACITY = 1

class LyricsLookupRunner(
    threadNamePrefix: String,
    private val callbackDispatcher: LyricsLookupCallbackDispatcher = mainThreadDispatcher(),
    private val executor: ExecutorService = ThreadPoolExecutor(
        DEFAULT_MAX_PARALLEL_LOOKUPS,
        DEFAULT_MAX_PARALLEL_LOOKUPS,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingDeque(DEFAULT_LOOKUP_QUEUE_CAPACITY),
        namedThreadFactory(threadNamePrefix),
        ThreadPoolExecutor.DiscardOldestPolicy()
    )
) {
    private val lock = Any()
    private var nextGeneration = 0L
    private var activeToken: LyricsLookupCancellationToken? = null
    private var activeHandle: LyricsLookupHandle? = null

    fun <T> submit(
        requestKey: String,
        lookup: (LyricsLookupCancellationToken) -> Result<T>,
        callback: (requestKey: String, result: Result<T>) -> Unit
    ): LyricsLookupHandle {
        val token = synchronized(lock) {
            cancelActiveLocked()
            val generation = ++nextGeneration
            LyricsLookupCancellationToken(requestKey = requestKey, generation = generation).also {
                activeToken = it
            }
        }

        val task = FutureTask lookupWorker@{
            val result = runCatching {
                token.throwIfCancellationRequested()
                val lookupResult = lookup(token)
                token.throwIfCancellationRequested()

                val lookupError = lookupResult.exceptionOrNull()
                if (lookupError is CancellationException) {
                    throw lookupError
                }
                lookupResult
            }.getOrElse { throwable ->
                if (throwable is CancellationException) {
                    return@lookupWorker
                }
                Result.failure(throwable)
            }

            callbackDispatcher.dispatch {
                if (!isStillActive(token)) return@dispatch
                clearIfActive(token)
                callback(token.requestKey, result)
            }
        }

        val handle = LyricsLookupHandle(token, task)
        val shouldExecute = synchronized(lock) {
            if (activeToken === token) {
                activeHandle = handle
                true
            } else {
                handle.cancel()
                false
            }
        }
        if (shouldExecute) {
            executor.execute(task)
        }
        return handle
    }

    fun cancelActive() {
        synchronized(lock) {
            cancelActiveLocked()
        }
    }

    fun shutdown() {
        cancelActive()
        executor.shutdownNow()
    }

    private fun cancelActiveLocked() {
        activeToken?.cancel()
        activeHandle?.cancel()
        activeToken = null
        activeHandle = null

        // Drop queued stale lookups so only the latest request can start after cancellation.
        (executor as? ThreadPoolExecutor)?.queue?.clear()
    }

    private fun isStillActive(token: LyricsLookupCancellationToken): Boolean {
        synchronized(lock) {
            return activeToken === token && !token.isCancellationRequested
        }
    }

    private fun clearIfActive(token: LyricsLookupCancellationToken) {
        synchronized(lock) {
            if (activeToken === token) {
                activeToken = null
                activeHandle = null
            }
        }
    }

    companion object {
        private fun mainThreadDispatcher(): LyricsLookupCallbackDispatcher {
            val mainHandler = Handler(Looper.getMainLooper())
            return LyricsLookupCallbackDispatcher { block -> mainHandler.post(block) }
        }

        private fun namedThreadFactory(prefix: String): ThreadFactory {
            val index = AtomicInteger(1)
            return ThreadFactory { runnable ->
                Thread(runnable, "$prefix-${index.getAndIncrement()}").apply {
                    isDaemon = true
                }
            }
        }
    }
}
