package com.andsi.airlyrics.lyrics.providers

object SpicyLyricsNative {
    init { LyricsNativeLibrary.ensureLoaded() }
    external fun fetchLyricsJson(title: String, artist: String, album: String, durationMs: Long, key: String, lookupId: Long): String
}
