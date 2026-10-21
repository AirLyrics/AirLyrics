package com.andsi.airlyrics.lyrics.providers

object AppleLyricsNative {
    init {
        LyricsNativeLibrary.ensureLoaded()
    }

    external fun fetchLyricsJson(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        mediaUserToken: String,
        developerToken: String,
        storefront: String,
        lookupId: Long
    ): String
}
