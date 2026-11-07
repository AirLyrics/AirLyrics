package com.andsi.airlyrics.lyrics

import android.icu.text.BreakIterator
import java.util.Locale

/** Retains complete unchanged tokens; only the edited interval receives estimated timings. */
internal object WordLyricsTextEditor {
    fun replaceText(line: WordByWordLine, text: String): WordByWordLine {
        require(line.endMs > line.startMs)
        require('\n' !in text && '\r' !in text)
        if (text == line.text || text == line.segments.joinToString("") { it.text }) return line.copy(text = text)
        if (text.isBlank()) return line.copy(text = text, segments = emptyList())
        val old = line.segments
        var prefix = 0
        var prefixLength = 0
        while (prefix < old.size && text.startsWith(old[prefix].text, prefixLength)) {
            prefixLength += old[prefix++].text.length
        }
        var suffix = 0
        var suffixLength = 0
        while (suffix < old.size - prefix) {
            val token = old[old.lastIndex - suffix].text
            val offset = text.length - suffixLength - token.length
            if (offset < prefixLength || !text.startsWith(token, offset)) break
            suffixLength += token.length
            suffix++
        }
        while (true) {
            val middleText = text.substring(prefixLength, text.length - suffixLength)
            val middle = splitText(middleText)
            if (middle.isEmpty()) {
                val kept = (old.take(prefix) + old.takeLast(suffix)).toMutableList()
                if (middleText.isNotEmpty() && kept.isNotEmpty()) {
                    val index = (prefix - 1).coerceAtLeast(0)
                    val segment = kept[index]
                    kept[index] = segment.copy(text = if (prefix > 0) segment.text + middleText else middleText + segment.text)
                }
                return line.copy(text = text, segments = kept)
            }
            val hasOldMiddle = prefix < old.size - suffix
            val start = (if (hasOldMiddle) old[prefix].startMs
                else old.getOrNull(prefix - 1)?.endMs ?: line.startMs).coerceIn(line.startMs, line.endMs)
            val end = (if (hasOldMiddle) old[old.lastIndex - suffix].endMs
                else old.getOrNull(old.size - suffix)?.startMs ?: line.endMs).coerceIn(line.startMs, line.endMs)
            if (end - start >= middle.size) {
                val weights = middle.map { graphemes(it).count { part -> !part.isBlank() }.coerceAtLeast(1) }
                val total = weights.sum().toLong()
                val spare = end - start - middle.size
                var elapsed = 0L
                var previousEnd = start
                val replacement = middle.mapIndexed { index, token ->
                    elapsed += weights[index]
                    val nextEnd = start + index + 1 + spare * elapsed / total
                    WordByWordSegment(token, previousEnd, nextEnd).also { previousEnd = nextEnd }
                }
                return line.copy(text = text, segments = old.take(prefix) + replacement + old.takeLast(suffix))
            }
            // An insertion at a touching boundary borrows a neighbour rather than creating zero-duration words.
            if (prefix > 0) prefixLength -= old[--prefix].text.length
            else if (suffix > 0) suffixLength -= old[old.size - suffix--].text.length
            else return line.copy(text = text, segments = listOf(WordByWordSegment(text, line.startMs, line.endMs)))
        }
    }

    /** CJK graphemes remain individually editable; other scripts keep words and their spaces. */
    private fun splitText(text: String): List<String> {
        val parts = mutableListOf<String>()
        val word = StringBuilder()
        var leadingSpace = ""
        fun flush() {
            if (word.isNotEmpty()) {
                parts += leadingSpace + word.toString()
                leadingSpace = ""
                word.setLength(0)
            }
        }
        for (part in graphemes(text)) {
            val point = part.codePointAt(0)
            val script = Character.UnicodeScript.of(point)
            val cjk = script in setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL)
            when {
                part.isBlank() -> {
                    flush()
                    if (parts.isEmpty()) leadingSpace += part else parts[parts.lastIndex] += part
                }
                !cjk && (Character.isLetterOrDigit(point) || part == "'" || part == "’") -> word.append(part)
                else -> {
                    flush()
                    val punctuation = Character.getType(point) in setOf(
                        Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
                        Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
                        Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                        Character.OTHER_PUNCTUATION.toInt())
                    if (punctuation && parts.isNotEmpty()) parts[parts.lastIndex] += part
                    else { parts += leadingSpace + part; leadingSpace = "" }
                }
            }
        }
        flush()
        return parts
    }

    private fun graphemes(text: String): List<String> {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        val result = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            result += text.substring(start, end)
            start = end
            end = iterator.next()
        }
        return result
    }
}
