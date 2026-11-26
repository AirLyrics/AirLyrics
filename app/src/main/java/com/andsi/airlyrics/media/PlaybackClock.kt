package com.andsi.airlyrics.media

internal class PlaybackClock(private val uptimeMillis: () -> Long) {
    private var positionMs = 0L
    private var anchorUptimeMs = 0L
    var isPlaying = false
        private set

    fun update(positionMs: Long, isPlaying: Boolean) {
        val now = uptimeMillis()
        val incomingPositionMs = positionMs.coerceAtLeast(0L)
        val estimatedPositionMs = estimateAt(now)
        val backtrackMs = estimatedPositionMs - incomingPositionMs
        val staleBacktrack = this.isPlaying && isPlaying && (backtrackMs in 1L..STALE_PLAYING_BACKTRACK_MS)

        this.positionMs = if (staleBacktrack) estimatedPositionMs else incomingPositionMs
        this.isPlaying = isPlaying
        anchorUptimeMs = now
    }

    fun estimatedPositionMs(): Long = estimateAt(uptimeMillis())

    fun pause(): Long {
        positionMs = estimatedPositionMs()
        isPlaying = false
        return positionMs
    }

    fun reset() {
        positionMs = 0L
        isPlaying = false
    }

    private fun estimateAt(now: Long): Long {
        if (!isPlaying) return positionMs
        val elapsed = (now - anchorUptimeMs).coerceAtLeast(0L)
        return positionMs.coerceAtMost(Long.MAX_VALUE - elapsed) + elapsed
    }

    private companion object {
        const val STALE_PLAYING_BACKTRACK_MS = 1_500L
    }
}
