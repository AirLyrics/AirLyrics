package com.andsi.airlyrics.lyrics

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cooperative cancellation token for lyrics lookups.
 *
 * The Rust/JNI provider calls are still blocking while they are inside native code, but every
 * lookup now has a real cancellable handle: replacing the song/source/refresh request marks the
 * old token as cancelled and interrupts its worker thread. Results from cancelled tokens are never
 * delivered back to the UI.
 */
class LyricsLookupCancellationToken internal constructor() {
    @Volatile
    var isCancellationRequested: Boolean = false
        private set

    internal fun cancel() {
        isCancellationRequested = true
    }

    fun throwIfCancellationRequested() {
        if (isCancellationRequested || Thread.currentThread().isInterrupted) {
            throw CancellationException("Lyrics lookup was cancelled")
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

    val isCancellationRequested: Boolean
        get() = token.isCancellationRequested
}

class LyricsLookupRunner(
    threadNamePrefix: String,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val executor: ExecutorService = Executors.newCachedThreadPool(namedThreadFactory(threadNamePrefix))
) {
    private val lock = Any()
    private var activeToken: LyricsLookupCancellationToken? = null
    private var activeHandle: LyricsLookupHandle? = null

    fun <T> submit(
        requestKey: String,
        lookup: (LyricsLookupCancellationToken) -> Result<T>,
        callback: (requestKey: String, result: Result<T>) -> Unit
    ): LyricsLookupHandle {
        val token = LyricsLookupCancellationToken()

        synchronized(lock) {
            cancelActiveLocked()
            activeToken = token
        }

        val future = executor.submit lookupWorker@{
            val result = runCatching {
                token.throwIfCancellationRequested()
                val lookupResult = lookup(token)
                token.throwIfCancellationRequested()
                lookupResult
            }.getOrElse { throwable ->
                if (throwable is CancellationException) {
                    return@lookupWorker
                }
                Result.failure(throwable)
            }

            mainHandler.post {
                if (!isStillActive(token)) return@post
                clearIfActive(token)
                callback(requestKey, result)
            }
        }

        val handle = LyricsLookupHandle(token, future)
        synchronized(lock) {
            if (activeToken === token) {
                activeHandle = handle
            } else {
                handle.cancel()
            }
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
        mainHandler.removeCallbacksAndMessages(null)
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
