package com.andsi.airlyrics.lyrics.display

import com.andsi.airlyrics.core.text.ChineseText
import com.andsi.airlyrics.lyrics.WordByWordSegment

internal fun conversionSegments(
    translatedText: String,
    segments: List<WordByWordSegment>,
): List<WordByWordSegment>? {
    if (segments.isEmpty()) return null
    val source = segments.joinToString("") { it.text }
    if (!ChineseText.isScriptConversion(source, translatedText)) {
        return null
    }
    val spokenTranslation = ChineseText.stripPerformanceRepeat(translatedText)
    // Reuse echo timing only when both renderings contain the echo.
    val sourceForm = ChineseText.normalizedForm(source)
    val tokensCarryEcho = sourceForm != ChineseText.normalizedForm(spokenTranslation) &&
        sourceForm == ChineseText.normalizedForm(translatedText)
    val spoken = ChineseText.stripPerformanceRepeat(source)
    val timed = when {
        tokensCarryEcho -> segments
        spoken.length == source.length -> segments
        else -> prefixSegments(segments, spoken.length)
    }
    if (timed.isEmpty()) return null
    val targetText = if (tokensCarryEcho) translatedText else spokenTranslation
    // A conversion phrase can straddle timed tokens, so convert the whole line first.
    val (convertedSource, boundaries) = ChineseText.simplifiedBoundaries(source)
    var sourceOffset = 0
    val mapped = mutableListOf<WordByWordSegment>()
    var cursor = 0
    for (segment in timed) {
        val segmentStart = sourceOffset
        val segmentEnd = segmentStart + segment.text.length
        sourceOffset = segmentEnd
        val boundaryStart = boundaries.getOrNull(segmentStart) ?: -1
        val boundaryEnd = boundaries.getOrNull(segmentEnd) ?: -1
        val target = when {
            boundaryStart >= 0 && boundaryEnd in boundaryStart..convertedSource.length ->
                ChineseText.normalizedForm(convertedSource.substring(boundaryStart, boundaryEnd))
            // Equal-length conversions can reuse token widths when a boundary is absent.
            boundaryStart >= 0 && convertedSource.length == source.length ->
                ChineseText.normalizedForm(
                    convertedSource.substring(
                        boundaryStart,
                        minOf(boundaryStart + segment.text.length, convertedSource.length)
                    )
                )
            else -> ChineseText.normalizedForm(segment.text)
        }
        if (target.isEmpty()) continue
        val consumed = StringBuilder()
        var matched = 0
        // Display punctuation and spaces belong to the text without consuming token characters.
        while (matched < target.length && cursor < targetText.length) {
            val character = targetText[cursor]
            consumed.append(character)
            cursor++
            if (!isIgnorable(character)) matched++
        }
        if (matched != target.length ||
            ChineseText.normalizedForm(consumed.toString()) != target
        ) return null
        mapped += segment.copy(text = consumed.toString())
    }
    while (cursor < targetText.length && isIgnorable(targetText[cursor])) {
        if (mapped.isEmpty()) return null
        val last = mapped.removeAt(mapped.lastIndex)
        mapped += last.copy(text = last.text + targetText[cursor])
        cursor++
    }
    return if (cursor == targetText.length) mapped else null
}

internal fun mapDisplaySegments(
    source: String,
    display: String,
    segments: List<WordByWordSegment>,
): List<WordByWordSegment>? {
    if (segments.joinToString("") { it.text } != source) return null
    // Timed tokens can omit spaces at boundaries, including before a sung echo.
    if (source.filterNot { it.isWhitespace() } == display.filterNot { it.isWhitespace() }) {
        var cursor = 0
        val mapped = segments.map { segment ->
            val start = cursor
            for (character in segment.text.filterNot { it.isWhitespace() }) {
                while (cursor < display.length && display[cursor].isWhitespace()) cursor++
                if (cursor >= display.length || display[cursor] != character) return null
                cursor++
            }
            segment.copy(text = display.substring(start, cursor))
        }.toMutableList()
        if (mapped.isEmpty()) return null
        val last = mapped.last()
        mapped[mapped.lastIndex] = last.copy(text = last.text + display.substring(cursor))
        return mapped
    }
    val (converted, sourceBoundaries) = ChineseText.simplifiedBoundaries(source)
    val (convertedDisplay, displayBoundaries) = ChineseText.simplifiedBoundaries(display)
    if (converted != convertedDisplay) return null
    val reverse = displayBoundaries.withIndex().filter { it.value >= 0 }
        .groupBy({ it.value }, { it.index })
    var offset = 0
    var previousEndMs = Long.MIN_VALUE
    return segments.map { segment ->
        if (segment.startMs < previousEndMs || segment.endMs <= segment.startMs) return null
        previousEndMs = segment.endMs
        val start = sourceBoundaries[offset]
        offset += segment.text.length
        val end = sourceBoundaries[offset]
        val displayStart = reverse[start]?.singleOrNull() ?: return null
        val displayEnd = reverse[end]?.singleOrNull() ?: return null
        if (start !in 0 until end || displayEnd <= displayStart) return null
        segment.copy(text = display.substring(displayStart, displayEnd))
    }
}

private fun isIgnorable(character: Char): Boolean =
    character.isWhitespace() ||
    character in "\uFF0C\u3002\uFF01\uFF1F\u3001\uFF1B\uFF1A,.!?;:\u00B7\u2026\u2014\uFF08\uFF09()\u3010\u3011[]"

private fun prefixSegments(
    segments: List<WordByWordSegment>,
    limit: Int,
): List<WordByWordSegment> {
    val out = mutableListOf<WordByWordSegment>()
    var count = 0
    for (segment in segments) {
        if (count >= limit) break
        val remaining = limit - count
        if (segment.text.length <= remaining) {
            out += segment
            count += segment.text.length
        } else {
            out += segment.copy(text = segment.text.substring(0, remaining))
            break
        }
    }
    return out
}
