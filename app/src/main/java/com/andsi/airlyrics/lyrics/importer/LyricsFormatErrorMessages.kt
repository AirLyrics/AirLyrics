package com.andsi.airlyrics.lyrics.importer

import android.content.Context
import com.andsi.airlyrics.R

internal fun Context.plainLyricsFormatErrorMessage(invalidLineNumbers: List<Int>): String {
    return if (invalidLineNumbers.isNotEmpty()) {
        val lines = invalidLineNumbers.take(8).joinToString(", ")
        val suffix = if (invalidLineNumbers.size > 8) "..." else ""
        getString(R.string.lyrics_plain_format_invalid_lines, lines, suffix)
    } else {
        getString(R.string.ui_plain_lrc_no_valid_line_error)
    }
}

internal fun Context.wordByWordLyricsFormatErrorMessage(invalidLineNumbers: List<Int>): String {
    return if (invalidLineNumbers.isNotEmpty()) {
        val lines = invalidLineNumbers.take(8).joinToString(", ")
        val suffix = if (invalidLineNumbers.size > 8) "..." else ""
        getString(R.string.lyrics_word_by_word_format_invalid_lines, lines, suffix)
    } else {
        getString(R.string.ui_no_valid_word_by_word_lyrics_line)
    }
}

internal fun Context.plainLyricsImportFormatErrorMessage(
    invalidLineNumbers: List<Int>
): String {
    return importFormatErrorMessage(
        invalidLineNumbers = invalidLineNumbers,
        emptyMessageRes = R.string.ui_import_no_valid_plain_lyrics
    )
}

internal fun Context.wordByWordLyricsImportFormatErrorMessage(
    invalidLineNumbers: List<Int>
): String {
    return importFormatErrorMessage(
        invalidLineNumbers = invalidLineNumbers,
        emptyMessageRes = R.string.ui_import_no_valid_word_by_word_lyrics
    )
}

private fun Context.importFormatErrorMessage(
    invalidLineNumbers: List<Int>,
    emptyMessageRes: Int
): String {
    if (invalidLineNumbers.isEmpty()) return getString(emptyMessageRes)

    val lines = invalidLineNumbers.take(8).joinToString(", ")
    val suffix = if (invalidLineNumbers.size > 8) "..." else ""
    return getString(R.string.lyrics_import_format_invalid_lines, lines, suffix)
}
