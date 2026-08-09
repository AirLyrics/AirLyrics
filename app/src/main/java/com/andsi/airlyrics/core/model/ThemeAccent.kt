package com.andsi.airlyrics.core.model

/** Stable app-wide accent choices. Persist [preferenceValue], never enum names. */
enum class ThemeAccent(val preferenceValue: String) {
    PINK("pink"),
    ORANGE("orange"),
    GREEN("green"),
    BLUE("blue"),
    PURPLE("purple");

    companion object {
        val DEFAULT = PINK

        fun fromPreferenceValue(value: String?): ThemeAccent {
            return entries.firstOrNull { it.preferenceValue == value } ?: DEFAULT
        }
    }
}
