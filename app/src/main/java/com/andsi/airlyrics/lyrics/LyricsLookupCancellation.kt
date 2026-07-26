package com.andsi.airlyrics.lyrics

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.RejectedExecutionException
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
    internal val isDone: Boolean
        get() = future.isDone

    internal val isCancelled: Boolean
        get() = future.isCancelled

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

internal fun createLyricsLookupExecutor(threadNamePrefix: String): ThreadPoolExecutor {
    return ThreadPoolExecutor(
        DEFAULT_MAX_PARALLEL_LOOKUPS,
        DEFAULT_MAX_PARALLEL_LOOKUPS,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingDeque(DEFAULT_LOOKUP_QUEUE_CAPACITY),
        namedThreadFactory(threadNamePrefix),
        ThreadPoolExecutor.AbortPolicy()
    )
}

private fun namedThreadFactory(prefix: String): ThreadFactory {
    val index = AtomicInteger(1)
    return ThreadFactory { runnable ->
        Thread(runnable, "$prefix-${index.getAndIncrement()}").apply {
            isDaemon = true
        }
    }
}

class LyricsLookupRunner(
    threadNamePrefix: String,
    private val callbackDispatcher: LyricsLookupCallbackDispatcher = mainThreadDispatcher(),
    private val executor: ExecutorService = createLyricsLookupExecutor(threadNamePrefix)
) {
    private val admissionLock = Any()
    private val lock = Any()
    private var nextGeneration = 0L
    private var activeToken: LyricsLookupCancellationToken? = null
    private var activeHandle: LyricsLookupHandle? = null
    private var closed = false

    fun <T> submit(
        requestKey: String,
        lookup: (LyricsLookupCancellationToken) -> Result<T>,
        callback: (requestKey: String, result: Result<T>) -> Unit
    ): LyricsLookupHandle {
        var rejectedDelivery: (() -> Unit)? = null
        val handle = synchronized(admissionLock) {
            val token = synchronized(lock) {
                LyricsLookupCancellationToken(
                    requestKey = requestKey,
                    generation = ++nextGeneration
                )
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
            val submittedHandle = LyricsLookupHandle(token, task)

            val shouldExecute = synchronized(lock) {
                if (closed) {
                    false
                } else {
                    cancelActiveLocked()
                    activeToken = token
                    activeHandle = submittedHandle
                    true
                }
            }
            if (!shouldExecute) {
                submittedHandle.cancel()
                return@synchronized submittedHandle
            }

            try {
                executor.execute(task)
            } catch (rejected: RejectedExecutionException) {
                task.cancel(false)
                rejectedDelivery = {
                    callbackDispatcher.dispatch {
                        if (!isStillActive(token)) return@dispatch
                        clearIfActive(token)
                        callback(token.requestKey, Result.failure(rejected))
                    }
                }
            } catch (failure: RuntimeException) {
                synchronized(lock) {
                    if (activeToken === token) {
                        activeToken = null
                        activeHandle = null
                    }
                }
                submittedHandle.cancel()
                throw failure
            }
            submittedHandle
        }
        rejectedDelivery?.invoke()
        return handle
    }

    fun cancelActive() {
        synchronized(lock) {
            cancelActiveLocked()
        }
    }

    fun shutdown() {
        val shouldShutdown = synchronized(admissionLock) {
            synchronized(lock) {
                if (closed) {
                    false
                } else {
                    closed = true
                    cancelActiveLocked()
                    true
                }
            }
        }
        if (!shouldShutdown) return

        val pending = executor.shutdownNow()
        pending.forEach { task ->
            (task as? Future<*>)?.cancel(true)
        }
    }

    private fun cancelActiveLocked() {
        activeToken?.cancel()
        activeHandle?.cancel()
        activeToken = null
        activeHandle = null

        // Cancel queued stale lookups so every submitted Future reaches a terminal state.
        val queue = (executor as? ThreadPoolExecutor)?.queue ?: return
        while (true) {
            val queued = queue.poll() ?: break
            (queued as? Future<*>)?.cancel(true)
        }
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
    }
}
