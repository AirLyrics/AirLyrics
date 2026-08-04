package com.andsi.airlyrics.lyrics

/** A pluggable plain-lyrics source. New online sources should implement this interface. */
interface PlainLyricsProvider {
    val id: String
    val name: String

    fun fetch(request: PlainLyricsSearchRequest): Result<LyricsProviderResult?>
}
