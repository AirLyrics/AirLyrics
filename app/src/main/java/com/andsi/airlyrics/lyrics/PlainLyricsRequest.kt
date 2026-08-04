package com.andsi.airlyrics.lyrics

import android.content.Context

/** Information required by a plain-lyrics provider to look up a song. */
data class PlainLyricsSearchRequest(
    val context: Context,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long,
    val cancellationToken: LyricsLookupCancellationToken? = null
)
