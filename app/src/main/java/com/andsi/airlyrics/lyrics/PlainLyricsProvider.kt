package com.andsi.airlyrics.lyrics

interface LyricsProvider {
    val id: String
    val name: String
    @Suppress("SameReturnValue")
    val supportsWordTiming: Boolean get() = false
    @Suppress("SameReturnValue")
    val continueOnFailure: Boolean get() = false

    /** Optional provider-owned cache. Never performs a network request. */
    fun cached(request: PlainLyricsSearchRequest): LyricsProviderResult? = null

    fun fetch(request: PlainLyricsSearchRequest): Result<LyricsProviderResult?>
}

typealias PlainLyricsProvider = LyricsProvider
