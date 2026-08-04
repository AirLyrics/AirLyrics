package com.andsi.airlyrics.lyrics.providers

object NeteaseLyricsNative {
    init {
        LyricsNativeLibrary.ensureLoaded()
    }

    external fun fetchBestLyricsJson(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        lookupId: Long,
        reserved: Boolean
    ): String
}
