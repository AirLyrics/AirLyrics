package com.andsi.airlyrics.lyrics

data class UntimedLyrics(val text: String, val translatedText: String? = null) {
    val hasContent: Boolean get() = text.isNotBlank() || !translatedText.isNullOrBlank()
    val readableText: String get() = listOfNotNull(text.takeIf { it.isNotEmpty() }, translatedText)
        .joinToString("\n\n")
}
