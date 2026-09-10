package com.andsi.airlyrics.lyrics.parser

import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import java.util.Locale

internal fun ParsedTtmlDocument.toPlainLrc(): String {
    return lines
        .filter { line ->
            line.text.isNotBlank() || line.translations.any { it.isNotBlank() }
        }
        .sortedBy { it.startMs }
        .joinToString("\n") { line ->
            val original = line.text.trim()
            val translations = line.translations
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && it != original }
                .distinct()
                .toList()
            val storedText = when {
                original.isNotBlank() && translations.isNotEmpty() -> {
                    "$original / ${translations.joinToString(" / ")}"
                }
                original.isNotBlank() -> original
                translations.isNotEmpty() -> translations.joinToString(" / ")
                else -> ""
            }
            "[${formatTtmlLrcTime(line.startMs)}]$storedText"
        }
}

internal fun ParsedTtmlDocument.toParsedWordByWordLyrics(): ParsedWordByWordLyrics {
    val visibleLines = lines.filter { line ->
        line.text.isNotBlank() || line.translations.any { it.isNotBlank() }
    }
    val hasCompleteWordTiming = visibleLines.isNotEmpty() && visibleLines.all { line ->
        line.text.isNotBlank() && line.segments.any { it.text.isNotBlank() }
    }
    val wordByWordLines = if (hasCompleteWordTiming) {
        visibleLines
            .sortedBy { it.startMs }
            .map { line ->
                WordByWordLine(
                    startMs = line.startMs,
                    endMs = line.endMs,
                    text = line.text,
                    segments = line.segments.map { segment ->
                        WordByWordSegment(
                            text = segment.text,
                            startMs = segment.startMs,
                            endMs = segment.endMs
                        )
                    }
                )
            }
    } else {
        emptyList()
    }

    val hasTranslation = lines.any { line ->
        line.translations.any { translation ->
            translation.isNotBlank() && translation.trim() != line.text.trim()
        }
    }

    return ParsedWordByWordLyrics(
        wordByWordLines = wordByWordLines,
        plainLrc = toPlainLrc(),
        hasTranslation = hasTranslation
    )
}

private fun formatTtmlLrcTime(timeMs: Long): String {
    val minutes = timeMs / 60_000L
    val seconds = (timeMs % 60_000L) / 1_000L
    val centiseconds = (timeMs % 1_000L) / 10L
    return "%02d:%02d.%02d".format(Locale.ROOT, minutes, seconds, centiseconds)
}
