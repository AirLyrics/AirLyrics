package com.andsi.airlyrics.lyrics

/** Text is preserved verbatim. Its lack of timing is a source format, not a parse failure. */
data class UntimedLyrics(val text: String, val translatedText: String? = null) {
    val hasContent: Boolean get() = text.isNotBlank() || !translatedText.isNullOrBlank()
    val readableText: String get() = listOfNotNull(text.takeIf { it.isNotEmpty() }, translatedText)
        .joinToString("\n\n")
}
