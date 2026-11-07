package com.andsi.airlyrics.lyrics

/**
 * Normalized lyrics: timed tracks or an explicitly untimed text document.
 * A provider may return a translated-only track when no original track is available.
 */
data class LyricsProviderResult(
    val plainProviderId: String,
    val plainProviderName: String,
    val plainLrc: String,
    val translatedLrc: String? = null,
    val wordByWordLines: List<WordByWordLine> = emptyList(),
    val matchedTitle: String = "",
    val matchedArtist: String = "",
    val matchedAlbum: String = "",
    val matchedDurationMs: Long = 0L,
    val access: LyricsAccess? = null,
    val manualLocal: Boolean = false,
    val selectedManually: Boolean = false,
    val pendingSpicyLyrics: PendingSpicyLyrics? = null,
    val untimedLyrics: UntimedLyrics? = null
) {
    init {
        require(untimedLyrics == null || (plainLrc.isEmpty() && translatedLrc.isNullOrEmpty() && wordByWordLines.isEmpty())) {
            "Untimed text cannot contain timed tracks"
        }
    }
    val isUntimed: Boolean get() = untimedLyrics != null
    val precision: LyricsPrecision get() = when {
        isUntimed -> LyricsPrecision.TEXT
        wordByWordLines.isNotEmpty() -> LyricsPrecision.WORD
        else -> LyricsPrecision.LINE
    }
    val storageText: String get() = untimedLyrics?.text
        ?: com.andsi.airlyrics.lyrics.parser.LrcParser.mergeOriginalAndTranslationForStorage(plainLrc, translatedLrc)
}

internal fun LyricsProviderResult.hasUsableLyrics(): Boolean {
    return untimedLyrics?.hasContent == true || plainLrc.isNotBlank() || !translatedLrc.isNullOrBlank()
}

/** Provider response held in memory until the repository chooses and commits it. */
data class PendingSpicyLyrics(val raw: String, val searchKey: String, val fetchedAtMs: Long)
