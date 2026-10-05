package com.andsi.airlyrics.ui.model

/** Content kept outside saved instance state; mutations are queued in call order. */
internal interface ReaderContent {
    fun save(id: String, text: String)
    suspend fun load(id: String): String?
    fun discard(id: String)
}
