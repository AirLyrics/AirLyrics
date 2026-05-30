package com.andsi.airlyrics.lyrics.providers

object MusixmatchLyricsNative {
    init {
        System.loadLibrary("airlyrics_lyrics")
    }

    external fun fetchBestLyricsJson(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        translationLanguageCode: String,
        reserved: Boolean
    ): String
}
