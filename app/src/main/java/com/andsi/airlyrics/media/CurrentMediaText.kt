package com.andsi.airlyrics.media

import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.media.model.CurrentMediaInfo

internal val CurrentMediaInfo.displayText: String
    get() = formatSongDisplayText(title, artist)

internal val SongIdentity.displayText: String
    get() = formatSongDisplayText(title, artist)

private fun formatSongDisplayText(title: String, artist: String): String {
    return if (artist.isBlank()) title else "$title - $artist"
}
