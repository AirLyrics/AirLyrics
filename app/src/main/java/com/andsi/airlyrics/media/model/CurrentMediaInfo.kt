package com.andsi.airlyrics.media.model

import java.util.concurrent.atomic.AtomicLong

/**
 * Current media state accepted by the app for lyrics operations.
 */
data class CurrentMediaInfo(
    val sourcePackage: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val isPlaying: Boolean,
    val positionMs: Long,
    val snapshotSequence: Long = UNSPECIFIED_SNAPSHOT_SEQUENCE
) {
    val isEmpty: Boolean
        get() = title.isBlank()

    companion object {
        const val UNSPECIFIED_SNAPSHOT_SEQUENCE = 0L

        val Empty = CurrentMediaInfo(
            sourcePackage = "",
            title = "",
            artist = "",
            album = "",
            durationMs = 0L,
            isPlaying = false,
            positionMs = 0L,
            snapshotSequence = UNSPECIFIED_SNAPSHOT_SEQUENCE
        )
    }
}

internal object MediaSnapshotSequencer {
    private val nextSequence = AtomicLong(CurrentMediaInfo.UNSPECIFIED_SNAPSHOT_SEQUENCE)

    fun next(): Long = nextSequence.incrementAndGet()
}
