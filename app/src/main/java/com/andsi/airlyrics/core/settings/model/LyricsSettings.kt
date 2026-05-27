package com.andsi.airlyrics.core.settings.model

/** User-facing lyrics lookup configuration. */
data class LyricsSettings(
    val source: String,
    val autoSaveLocal: Boolean
)

data class LyricsSourceOption(
    val key: String,
    val title: String,
    val description: String
)
