package com.andsi.airlyrics.floating

import com.andsi.airlyrics.lyrics.WordByWordLine
import kotlin.math.floor

/**
 * A wrap-safe karaoke highlight front. UTF-16 offsets are used because they are consumed by
 * Android spans, while progress advances by Unicode code point so surrogate pairs stay intact.
 */
internal data class WordByWordHighlightProgress(
    val completedEnd: Int = 0,
    val activeStart: Int = 0,
    val activeEnd: Int = 0,
    val activeFraction: Float = 0f
) {
    val hasActiveCharacter: Boolean
        get() = activeEnd > activeStart && activeFraction > 0f
}

internal fun wordByWordHighlightProgress(
    line: WordByWordLine,
    displayText: String,
    positionMs: Long
): WordByWordHighlightProgress {
    if (displayText.isBlank() || positionMs <= line.startMs) {
        return WordByWordHighlightProgress()
    }
    if (positionMs >= line.endMs) {
        return WordByWordHighlightProgress(completedEnd = displayText.length)
    }

    var searchStart = 0
    var completedEnd = 0
    var matchedSegment = false

    for (segment in line.segments) {
        val segmentText = segment.text.trim()
        if (segmentText.isBlank()) continue

        val segmentStart = displayText.indexOf(segmentText, searchStart).takeIf { it >= 0 }
            ?: displayText.indexOf(segmentText).takeIf { it >= 0 }
            ?: continue
        val segmentEnd = segmentStart + segmentText.length
        searchStart = segmentEnd
        matchedSegment = true

        when {
            positionMs >= segment.endMs -> completedEnd = segmentEnd
            positionMs >= segment.startMs -> {
                val durationMs = (segment.endMs - segment.startMs).coerceAtLeast(1L)
                val progress = ((positionMs - segment.startMs).toFloat() / durationMs.toFloat())
                    .coerceIn(0f, 1f)
                return progressWithinRange(
                    text = displayText,
                    rangeStart = segmentStart,
                    rangeEnd = segmentEnd,
                    progress = progress,
                    completedBeforeRange = completedEnd.coerceAtLeast(segmentStart)
                )
            }

            else -> return WordByWordHighlightProgress(completedEnd = completedEnd)
        }
    }

    if (matchedSegment) {
        return WordByWordHighlightProgress(completedEnd = completedEnd)
    }

    val durationMs = (line.endMs - line.startMs).coerceAtLeast(1L)
    val progress = ((positionMs - line.startMs).toFloat() / durationMs.toFloat())
        .coerceIn(0f, 1f)
    return progressWithinRange(
        text = displayText,
        rangeStart = 0,
        rangeEnd = displayText.length,
        progress = progress,
        completedBeforeRange = 0
    )
}

private fun progressWithinRange(
    text: String,
    rangeStart: Int,
    rangeEnd: Int,
    progress: Float,
    completedBeforeRange: Int
): WordByWordHighlightProgress {
    val boundaries = codePointBoundaries(text, rangeStart, rangeEnd)
    val characterCount = boundaries.lastIndex
    if (characterCount <= 0) {
        return WordByWordHighlightProgress(completedEnd = completedBeforeRange)
    }

    val continuousCharacterPosition = progress.coerceIn(0f, 1f) * characterCount
    val completedCharacters = floor(continuousCharacterPosition).toInt()
        .coerceIn(0, characterCount)
    val completedEnd = maxOf(completedBeforeRange, boundaries[completedCharacters])
    if (completedCharacters == characterCount) {
        return WordByWordHighlightProgress(completedEnd = completedEnd)
    }

    val activeFraction = smoothStep(continuousCharacterPosition - completedCharacters)
    return WordByWordHighlightProgress(
        completedEnd = completedEnd,
        activeStart = boundaries[completedCharacters],
        activeEnd = boundaries[completedCharacters + 1],
        activeFraction = activeFraction
    )
}

private fun codePointBoundaries(text: String, start: Int, end: Int): IntArray {
    val boundaries = mutableListOf(start)
    var index = start
    while (index < end) {
        index = (index + Character.charCount(text.codePointAt(index))).coerceAtMost(end)
        boundaries += index
    }
    return boundaries.toIntArray()
}

private fun smoothStep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
