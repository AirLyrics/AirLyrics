package com.andsi.airlyrics.lyrics

import com.andsi.airlyrics.core.model.LyricsSearchMode
import com.andsi.airlyrics.core.model.SongIdentity
import android.content.Context

/** Information required by a plain-lyrics provider to look up a song. */
data class PlainLyricsSearchRequest(
    val context: Context,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long,
    val cancellationToken: LyricsLookupCancellationToken? = null,
    val searchMode: LyricsSearchMode = LyricsSearchMode.Original,
    val originalSong: SongIdentity? = null
)
