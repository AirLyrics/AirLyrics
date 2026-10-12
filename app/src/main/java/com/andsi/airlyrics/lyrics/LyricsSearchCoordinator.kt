package com.andsi.airlyrics.lyrics

import com.andsi.airlyrics.core.model.LyricsSearchMode
import com.andsi.airlyrics.core.model.SongIdentity
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Shared ownership of searches for the same song, including their durable commit. */
internal object LyricsSearchCoordinator {
    private val lock = ReentrantLock()
    private val searchesChanged = lock.newCondition()
    private val searches = mutableMapOf<String, MutableList<Lease>>()
    private val epochs = mutableMapOf<String, AtomicLong>()
    private val observers = mutableSetOf<(SongIdentity) -> Unit>()

    class Lease internal constructor(
        val song: SongIdentity,
        val token: LyricsLookupCancellationToken,
        val origin: LyricsSearchOrigin,
        var mode: LyricsSearchMode
    ) : AutoCloseable {
        internal var active = true
        internal var cancellation: LyricsLookupCancellationRegistration? = null

        fun <T> commit(block: () -> T): T = lock.withLock {
            token.throwIfCancellationRequested()
            if (!active) throw CancellationException("Superseded lyrics search")
            block()
        }

        override fun close() {
            val notify = lock.withLock {
                if (!active) return
                active = false
                val key = song.copy(durationMs = 0L).storageKey()
                searches[key]?.let { entries ->
                    entries.remove(this)
                    if (entries.isEmpty()) searches.remove(key)
                }
                searchesChanged.signalAll()
                origin == LyricsSearchOrigin.Manual
            }
            cancellation?.dispose()
            if (notify) notifyObservers(song)
        }
    }

    fun begin(song: SongIdentity, token: LyricsLookupCancellationToken, origin: LyricsSearchOrigin, mode: LyricsSearchMode): Lease {
        val retired = mutableListOf<Lease>()
        val lease = lock.withLock {
            val key = song.copy(durationMs = 0L).storageKey()
            while (origin == LyricsSearchOrigin.Automatic && searches[key]?.any { it.origin == LyricsSearchOrigin.Manual } == true) {
                token.throwIfCancellationRequested()
                searchesChanged.await(100, TimeUnit.MILLISECONDS)
            }
            token.throwIfCancellationRequested()
            searches[key]?.firstOrNull { it.token === token }?.let { return it }
            val epoch = epochs.getOrPut(key) { AtomicLong() }
            val generation = if (origin == LyricsSearchOrigin.Manual) epoch.incrementAndGet() else epoch.get()
            token.searchIsCurrent = { epoch.get() == generation }
            if (origin == LyricsSearchOrigin.Manual) {
                retired += searches.remove(key).orEmpty()
                retired.forEach { it.active = false }
            }
            Lease(song, token, origin, mode).also { searches.getOrPut(key) { mutableListOf() }.add(it) }
        }
        // Native cancellation callbacks must never run under the commit lock.
        retired.forEach { it.cancellation?.dispose(); it.token.cancel() }
        lease.cancellation = token.invokeOnCancellation { lease.close() }
        if (origin == LyricsSearchOrigin.Manual) notifyObservers(song)
        return lease
    }

    fun current(token: LyricsLookupCancellationToken): Lease? = lock.withLock {
        searches.values.asSequence().flatten().firstOrNull { it.token === token && it.active }
    }

    fun manualMode(song: SongIdentity): LyricsSearchMode? = lock.withLock {
        searches[song.copy(durationMs = 0L).storageKey()]?.firstOrNull { it.origin == LyricsSearchOrigin.Manual }?.mode
    }

    fun updateMode(token: LyricsLookupCancellationToken, mode: LyricsSearchMode) {
        val song = lock.withLock {
            current(token)?.takeIf { it.origin == LyricsSearchOrigin.Manual && it.mode != mode }?.let {
                it.mode = mode
                it.song
            }
        }
        if (song != null) notifyObservers(song)
    }

    fun observe(observer: (SongIdentity) -> Unit): AutoCloseable {
        lock.withLock { observers.add(observer) }
        return AutoCloseable { lock.withLock { observers.remove(observer) } }
    }

    private fun notifyObservers(song: SongIdentity) {
        val listeners = lock.withLock { observers.toList() }
        listeners.forEach { it(song) }
    }
}

enum class LyricsSearchOrigin { Automatic, Manual }
