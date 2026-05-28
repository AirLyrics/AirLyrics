package com.andsi.airlyrics.floating.model

/**
 * Snapshot of the currently accepted media session.
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

    val displayText: String
        get() = if (artist.isNotBlank()) {
            "♪ $title - $artist"
        } else {
            "♪ $title"
        }

    fun lyricsKey(extra: String? = null): String {
        val base = "$sourcePackage|$title|$artist|$album|${durationMs / 1000L}"
        return if (extra == null) base else "$base|$extra"
    }

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
