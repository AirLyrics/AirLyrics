package com.andsi.airlyrics.lyrics

/** A pluggable lyrics source. New online sources should implement this interface. */
interface LyricsProvider {
    val id: String
    val name: String

    fun fetch(request: LyricsSearchRequest): Result<LyricsProviderResult?>
}
