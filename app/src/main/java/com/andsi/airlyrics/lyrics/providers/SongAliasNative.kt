package com.andsi.airlyrics.lyrics.providers

object SongAliasNative {
    init {
        LyricsNativeLibrary.ensureLoaded()
    }

    external fun registerJson(json: String): Boolean
}
