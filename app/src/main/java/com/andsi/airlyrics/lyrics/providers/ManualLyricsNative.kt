package com.andsi.airlyrics.lyrics.providers

internal object ManualLyricsNative {
    init { LyricsNativeLibrary.ensureLoaded() }
    external fun requestJson(request: String, key: String, lookupId: Long): String
}
