package com.andsi.airlyrics.lyrics

import com.andsi.airlyrics.core.model.LyricsAttribution

enum class LyricsPrecision { LINE, WORD }

/** This policy follows the payload, including edited and line-only derivatives. */
data class LyricsAccess(
    val fetchedAtMs: Long,
    val expiresAtMs: Long,
    val cacheKey: String,
    val attribution: LyricsAttribution,
    val allowExport: Boolean = false
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs < fetchedAtMs || nowMs >= expiresAtMs
}
