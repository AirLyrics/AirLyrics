package com.andsi.airlyrics.core.model

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
    val fontWeight: Int = FloatingLyricsFontWeight.DEFAULT,
    val glow: LyricsGlowStyle = LyricsGlowStyle(),
    val backgroundBlurPercent: Int = 0,
    val customFontId: String? = null
) {
    val fontId: String
        get() = if (fontFamily == FloatingLyricsFontFamily.CUSTOM) {
            customFontId ?: FloatingLyricsFontFamily.CUSTOM.key
        } else fontFamily.key
}

enum class FloatingLyricsFontFamily(val key: String) {
    SYSTEM_DEFAULT("system_default"),
    SERIF("serif"),
    ROUNDED("swei_gothic_sc"),
    HANDWRITING("wenkai"),
    CUSTOM("custom");

    companion object {
        fun fromKey(key: String?): FloatingLyricsFontFamily {
            return entries.firstOrNull { it.key == key } ?: SYSTEM_DEFAULT
        }
    }
}

object FloatingLyricsFontWeight {
    const val MIN = 100
    const val MAX = 900
    const val STEP = 100
    const val DEFAULT = 400
    const val LEVEL_MIN = 1
    const val LEVEL_MAX = 9
    const val LEVEL_STEP = 1

    fun normalize(weight: Int): Int {
        val clamped = weight.coerceIn(MIN, MAX)
        return ((clamped + STEP / 2) / STEP * STEP).coerceIn(MIN, MAX)
    }

    fun toLevel(weight: Int): Int = normalize(weight) / STEP

    fun fromLevel(level: Int): Int = level.coerceIn(LEVEL_MIN, LEVEL_MAX) * STEP
}

data class FloatingLyricsPreset(
    val key: String,
    val title: String
)
