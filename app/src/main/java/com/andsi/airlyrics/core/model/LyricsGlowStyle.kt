package com.andsi.airlyrics.core.model

/** Shared by line and word highlighting; percentages scale a font-relative halo. */
data class LyricsGlowStyle(
    val enabled: Boolean = true,
    val strengthPercent: Int = 100,
    val spreadPercent: Int = 100
) {
    fun normalized() = copy(
        strengthPercent = strengthPercent.coerceIn(0, 200),
        spreadPercent = spreadPercent.coerceIn(50, MAX_SPREAD_PERCENT)
    )

    companion object {
        const val MAX_SPREAD_PERCENT = 200
    }
}
