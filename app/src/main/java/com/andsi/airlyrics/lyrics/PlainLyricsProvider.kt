package com.andsi.airlyrics.lyrics

/** A pluggable lyrics source. New online sources should implement this interface. */
interface LyricsProvider {
    val id: String
    val name: String
    val supportsWordTiming: Boolean get() = false
    val continueOnFailure: Boolean get() = false

    /** Optional provider-owned cache. Never performs a network request. */
    fun cached(request: PlainLyricsSearchRequest): LyricsProviderResult? = null

    fun fetch(request: PlainLyricsSearchRequest): Result<LyricsProviderResult?>
}

/** Source-compatible adapter for existing plain providers. */
typealias PlainLyricsProvider = LyricsProvider
