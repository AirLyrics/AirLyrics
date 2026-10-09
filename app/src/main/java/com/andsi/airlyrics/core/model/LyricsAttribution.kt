package com.andsi.airlyrics.core.model

/** Content credit, kept separate from editable lyric text. */
data class LyricsCredit(val name: String, val url: String? = null)
data class LyricsAttribution(val provider: String, val credits: List<LyricsCredit> = emptyList())
