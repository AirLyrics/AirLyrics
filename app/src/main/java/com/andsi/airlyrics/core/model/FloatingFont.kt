package com.andsi.airlyrics.core.model

import java.io.IOException

data class FloatingFont(
    val id: String,
    val family: FloatingLyricsFontFamily,
    val displayName: String?,
    val source: FloatingFontSource,
    val available: Boolean,
    val sizeBytes: Long? = null,
    val collectionId: String? = null
)

data class FloatingFontCollection(
    val id: String,
    val displayName: String,
    val sizeBytes: Long
)

enum class FloatingFontSource {
    SYSTEM, PRESET, DOWNLOAD, IMPORTED
}

internal class FloatingFontNetworkException(cause: IOException) : IOException(cause)
