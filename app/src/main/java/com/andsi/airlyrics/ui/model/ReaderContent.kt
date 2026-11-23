package com.andsi.airlyrics.ui.model

internal interface ReaderContent {
    fun save(id: String, text: String)
    suspend fun load(id: String): String?
    fun discard(id: String)
}
