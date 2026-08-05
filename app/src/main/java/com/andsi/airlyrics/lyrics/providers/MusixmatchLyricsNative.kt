package com.andsi.airlyrics.lyrics.providers

object MusixmatchLyricsNative {
    init {
        LyricsNativeLibrary.ensureLoaded()
    }

    external fun fetchBestLyricsJson(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        translationLanguageCode: String,
        lookupId: Long
    ): String
}
