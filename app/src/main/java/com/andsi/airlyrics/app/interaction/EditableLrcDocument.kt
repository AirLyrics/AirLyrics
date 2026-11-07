package com.andsi.airlyrics.app.interaction

import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordLyricsDocument

/** A lossless draft view of physical LRC rows. Only simple timed rows are rewritten. */
internal class EditableLrcDocument private constructor(
    val rows: MutableList<Row>,
    private val lineBreak: String,
    private val wordDocument: WordLyricsDocument? = null
) {
    internal class Row(
        var raw: String,
        var ending: String,
        var timestamp: String?,
        var lyric: String,
        var wordLine: WordByWordLine? = null
    ) {
        val isTimed: Boolean get() = timestamp != null
        val isVisible: Boolean get() = isTimed || raw.isNotBlank()
    }

    val isWordByWord: Boolean get() = wordDocument != null

    fun serialize(): String = wordDocument?.copy(lines = rows.mapNotNull { it.wordLine })?.serialize()
        ?: buildString { rows.forEach { append(it.raw); append(it.ending) } }

    fun updateWord(row: Row, line: WordByWordLine) {
        row.wordLine = line
        row.timestamp = "[${WordLyricsDocument.time(line.startMs)}]"
        row.lyric = line.text
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

    fun append(timestamp: String, lyric: String): Row {
        if (rows.isNotEmpty()) return insertAfter(rows.lastIndex, timestamp, lyric)
        return Row(timestamp + lyric, "", timestamp, lyric).also(rows::add)
    }

    fun removeAt(index: Int) {
        val removed = rows.removeAt(index)
        // A final row without a line break owns the document's lack of a trailing break.
        if (index == rows.size && removed.ending.isEmpty()) rows.lastOrNull()?.ending = ""
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

        fun fromWords(text: String): EditableLrcDocument {
            val document = WordLyricsDocument.parse(text)
            return EditableLrcDocument(document.lines.map { line ->
                Row("", "", "[${WordLyricsDocument.time(line.startMs)}]", line.text, line)
            }.toMutableList(), "\n", document)
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
