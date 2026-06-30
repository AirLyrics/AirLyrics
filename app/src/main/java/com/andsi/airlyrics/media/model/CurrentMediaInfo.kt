package com.andsi.airlyrics.media.model

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
    val positionMs: Long
) {
    val isEmpty: Boolean
        get() = title.isBlank()

    companion object {
        val Empty = CurrentMediaInfo(
            sourcePackage = "",
            title = "",
            artist = "",
            album = "",
            durationMs = 0L,
            isPlaying = false,
            positionMs = 0L
        )
    }
}
