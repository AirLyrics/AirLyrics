package com.andsi.airlyrics.media

import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.media.model.CurrentMediaInfo

fun CurrentMediaInfo.toSongIdentity(): SongIdentity {
    return SongIdentity(
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs
    )
}
