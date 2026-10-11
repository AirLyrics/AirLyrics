package com.andsi.airlyrics.lyrics

import com.andsi.airlyrics.core.model.SongIdentity
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

/** Shared ownership of searches for the same song, including their durable commit. */
internal object LyricsSearchCoordinator {
    private val lock = Object()
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

        fun <T> commit(block: () -> T): T = synchronized(lock) {
            token.throwIfCancellationRequested()
            if (!active) throw CancellationException("Superseded lyrics search")
            block()
        }

        override fun close() {
            val notify = synchronized(lock) {
                if (!active) return
                active = false
                val key = song.copy(durationMs = 0L).storageKey()
                searches[key]?.let { entries ->
                    entries.remove(this)
                    if (entries.isEmpty()) searches.remove(key)
                }
                lock.notifyAll()
                origin == LyricsSearchOrigin.Manual
            }
            cancellation?.dispose()
            if (notify) notifyObservers(song)
        }
    }

    fun begin(song: SongIdentity, token: LyricsLookupCancellationToken, origin: LyricsSearchOrigin, mode: LyricsSearchMode): Lease {
        val retired = mutableListOf<Lease>()
        val lease = synchronized(lock) {
            val key = song.copy(durationMs = 0L).storageKey()
            while (origin == LyricsSearchOrigin.Automatic && searches[key]?.any { it.origin == LyricsSearchOrigin.Manual } == true) {
                token.throwIfCancellationRequested()
                lock.wait(100)
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

    fun current(token: LyricsLookupCancellationToken): Lease? = synchronized(lock) {
        searches.values.asSequence().flatten().firstOrNull { it.token === token && it.active }
    }

    fun manualMode(song: SongIdentity): LyricsSearchMode? = synchronized(lock) {
        searches[song.copy(durationMs = 0L).storageKey()]?.firstOrNull { it.origin == LyricsSearchOrigin.Manual }?.mode
    }

    fun updateMode(token: LyricsLookupCancellationToken, mode: LyricsSearchMode) {
        val song = synchronized(lock) {
            current(token)?.takeIf { it.origin == LyricsSearchOrigin.Manual && it.mode != mode }?.let {
                it.mode = mode
                it.song
            }
        }
        if (song != null) notifyObservers(song)
    }

    fun observe(observer: (SongIdentity) -> Unit): AutoCloseable {
        synchronized(lock) { observers.add(observer) }
        return AutoCloseable { synchronized(lock) { observers.remove(observer) } }
    }

    private fun notifyObservers(song: SongIdentity) {
        val listeners = synchronized(lock) { observers.toList() }
        listeners.forEach { it(song) }
    }
}

enum class LyricsSearchOrigin { Automatic, Manual }
