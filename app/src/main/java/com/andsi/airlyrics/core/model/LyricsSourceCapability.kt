package com.andsi.airlyrics.core.model

/** Configuration syntax only; server authorization is checked by the provider. */
internal object LyricsSourceCapability {
    private val spicyKey = Regex("sl_pk_[A-Za-z0-9_-]+")
    fun spicyConfigured(key: String): Boolean = spicyKey.matches(key)
}
