package com.andsi.airlyrics.lyrics.providers

object LrclibLyricsNative {
    init {
        LyricsNativeLibrary.ensureLoaded()
    }

    external fun fetchBestLyricsJson(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        lookupId: Long
    ): String
}
