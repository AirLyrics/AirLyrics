package com.andsi.airlyrics.app.interaction

import com.andsi.airlyrics.lyrics.parser.LrcParser

/** A lossless draft view of physical LRC rows. Only simple timed rows are rewritten. */
internal class EditableLrcDocument private constructor(
    val rows: MutableList<Row>,
    private val lineBreak: String
) {
    internal class Row(
        var raw: String,
        var ending: String,
        var timestamp: String?,
        var lyric: String
    ) {
        val isTimed: Boolean get() = timestamp != null
        val isVisible: Boolean get() = isTimed || raw.isNotBlank()
    }

    fun serialize(): String = buildString {
        rows.forEach { append(it.raw); append(it.ending) }
    }

    fun update(index: Int, timestamp: String, lyric: String) {
        rows[index].apply {
            this.timestamp = timestamp
            this.lyric = lyric
            raw = timestamp + lyric
        }
    }

    fun insertAfter(index: Int, timestamp: String, lyric: String): Row {
        val preceding = rows[index]
        val newEnding = preceding.ending
        if (newEnding.isEmpty()) preceding.ending = lineBreak
        return Row(timestamp + lyric, newEnding, timestamp, lyric).also { rows.add(index + 1, it) }
    }

    companion object {
        private val timedRow = Regex("""^[ \t]*(\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?])(.*)$""")
        private val lineBreaks = Regex("\r\n|\n|\r")

        fun from(text: String): EditableLrcDocument {
            val rows = mutableListOf<Row>()
            var start = 0
            var preferredBreak = "\n"
            lineBreaks.findAll(text).forEach { breakMatch ->
                if (rows.isEmpty()) preferredBreak = breakMatch.value
                rows += row(text.substring(start, breakMatch.range.first), breakMatch.value)
                start = breakMatch.range.last + 1
            }
            if (start < text.length) rows += row(text.substring(start), "")
            return EditableLrcDocument(rows, preferredBreak)
        }

        /** Keep the user's precision and separator; use the parser to check actual time bounds. */
        fun normalizedTimestamp(input: String): String? {
            val tag = timeTagInput(input)
            return tag.takeIf { LrcParser.validateTimeTag(it) == LrcParser.TimeTagValidation.VALID }
        }

        fun timeTagInput(input: String): String {
            val value = input.trim()
            return if (value.startsWith('[') || value.endsWith(']')) value else "[$value]"
        }

        /** A multiline field maps to the inline translation form accepted by the existing parser. */
        fun normalizedLyric(input: String): String {
            val lines = input.replace("\r\n", "\n").replace('\r', '\n')
            if ('\n' !in lines) return input
            return lines.split('\n')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .joinToString(" / ")
        }

        fun canStoreAsOneRow(timestamp: String, lyric: String): Boolean =
            LrcParser.parse(timestamp + lyric).size == 1 &&
                LrcParser.validateForStorage(timestamp + lyric).invalidLineNumbers.isEmpty()

        private fun row(raw: String, ending: String): Row {
            val match = timedRow.matchEntire(raw)
            val tag = match?.groupValues?.get(1)
                ?.takeIf { LrcParser.parse(raw).size == 1 }
            return Row(raw, ending, tag, if (tag == null) raw else match.groupValues[2])
        }
    }
}
