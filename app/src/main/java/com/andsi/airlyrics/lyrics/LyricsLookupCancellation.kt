package com.andsi.airlyrics.lyrics

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cooperative cancellation token for lyrics lookups.
 *
 * Cancelling a lookup marks the token and interrupts the worker. Kotlin/file based lookup stages
 * check the token between steps, while Rust/JNI provider calls may only stop after the native call
 * returns or times out. Results from cancelled tokens are never delivered back to the UI.
 */
class LyricsLookupCancellationToken internal constructor(
    val requestKey: String,
    val generation: Long
) {
    @Volatile
    var isCancellationRequested: Boolean = false
        private set

    internal fun cancel() {
        isCancellationRequested = true
    }

    fun throwIfCancellationRequested() {
        if (isCancellationRequested || Thread.currentThread().isInterrupted) {
            throw CancellationException("Lyrics lookup was cancelled: $requestKey#$generation")
        }
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

    val requestKey: String
        get() = token.requestKey

    val generation: Long
        get() = token.generation

    val isCancellationRequested: Boolean
        get() = token.isCancellationRequested
}

fun interface LyricsLookupCallbackDispatcher {
    fun dispatch(block: () -> Unit)
}

class LyricsLookupRunner(
    threadNamePrefix: String,
    private val callbackDispatcher: LyricsLookupCallbackDispatcher = mainThreadDispatcher(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(namedThreadFactory(threadNamePrefix))
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

        val task = FutureTask<Unit>(lookupWorker@{
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
        })

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
