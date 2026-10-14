package com.andsi.airlyrics.lyrics.providers

object SpicyLyricsNative {
    init { LyricsNativeLibrary.ensureLoaded() }
    // Called by scripts/lyrics_benchmark/Benchmark.java outside the app source set.
    external fun fetchLyricsJson(title: String, artist: String, album: String, durationMs: Long, key: String, lookupId: Long): String
    external fun fetchLyricsForSearchJson(title: String, artist: String, album: String, durationMs: Long, key: String, lookupId: Long, international: Boolean): String
}
