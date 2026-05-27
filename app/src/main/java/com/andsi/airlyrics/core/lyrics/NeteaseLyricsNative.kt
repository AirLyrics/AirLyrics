package com.andsi.airlyrics

object NeteaseLyricsNative {
    init {
        System.loadLibrary("airlyrics_lyrics")
    }

    external fun fetchBestLyricsJson(
        title: String,
        artist: String,
        album: String,
        durationMs: Long
    ): String
}
