package com.andsi.airlyrics.core.model

/** Stable preference values for the app-wide button appearance. */
enum class ButtonStyle(val preferenceValue: String) {
    SOLID("solid"),
    TRANSPARENT("transparent");

    companion object {
        val DEFAULT = TRANSPARENT

        fun fromPreferenceValue(value: String?): ButtonStyle =
            entries.firstOrNull { it.preferenceValue == value } ?: DEFAULT
    }
}
