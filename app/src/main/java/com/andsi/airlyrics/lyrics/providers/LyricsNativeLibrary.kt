package com.andsi.airlyrics.lyrics.providers

internal object LyricsNativeLibrary {
    private const val LIBRARY_NAME = "airlyrics_lyrics"

    init {
        System.loadLibrary(LIBRARY_NAME)
    }

    fun ensureLoaded() = Unit
}
