package com.andsi.airlyrics.core.model

internal object LyricsSourceCapability {
    private val spicyKey = Regex("sl_pk_[A-Za-z0-9_-]+")
    fun spicyConfigured(key: String): Boolean = spicyKey.matches(key)
}
