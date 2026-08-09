package com.andsi.airlyrics.core.model

/**
 * Visual configuration for the floating lyrics window.
 *
 * Keep this file data-only so UI pages and the floating window controller can
 * share the same contract without depending on each other's implementation.
 */
data class FloatingLyricsStyle(
    val presetName: String,
    val textSizeSp: Float,
    val textColor: Int,
    val wordByWordHighlightColor: Int,
    val shadowColor: Int,
    val shadowRadius: Float,
    val backgroundEnabled: Boolean,
    val backgroundColor: Int,
    val backgroundAlpha: Int,
    val cornerRadiusDp: Int,
    val paddingHorizontalDp: Int,
    val paddingVerticalDp: Int,
    val maxWidthPercent: Int,
    val gravity: Int,
    val fontFamily: FloatingLyricsFontFamily = FloatingLyricsFontFamily.SYSTEM_DEFAULT,
    val fontWeight: Int = FloatingLyricsFontWeight.DEFAULT
)

/** Font sources that can be applied only to the floating lyrics text. */
enum class FloatingLyricsFontFamily(val key: String) {
    SYSTEM_DEFAULT("system_default"),
    SANS_SERIF("sans_serif"),
    SERIF("serif"),
    MONOSPACE("monospace"),
    CUSTOM("custom");

    companion object {
        fun fromKey(key: String?): FloatingLyricsFontFamily {
            return entries.firstOrNull { it.key == key } ?: SYSTEM_DEFAULT
        }
    }
}

/** Shared bounds for the fine-grained floating lyrics font-weight control. */
object FloatingLyricsFontWeight {
    const val MIN = 100
    const val MAX = 900
    const val STEP = 10
    const val DEFAULT = 400

    fun normalize(weight: Int): Int {
        val clamped = weight.coerceIn(MIN, MAX)
        return ((clamped + STEP / 2) / STEP * STEP).coerceIn(MIN, MAX)
    }
}

/** A named preset shown in the floating-window settings page. */
data class FloatingLyricsPreset(
    val key: String,
    val title: String
)
