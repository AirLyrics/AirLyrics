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

internal fun Context.enhancedLyricsFormatErrorMessage(invalidLineNumbers: List<Int>): String {
    return if (invalidLineNumbers.isNotEmpty()) {
        val lines = invalidLineNumbers.take(8).joinToString(", ")
        val suffix = if (invalidLineNumbers.size > 8) "..." else ""
        getString(R.string.lyrics_enhanced_format_invalid_lines, lines, suffix)
    } else {
        getString(R.string.ui_no_valid_enhanced_lrc_line_was_found)
    }
}
