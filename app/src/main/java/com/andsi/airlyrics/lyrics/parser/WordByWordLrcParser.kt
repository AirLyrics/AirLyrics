package com.andsi.airlyrics.lyrics.parser

import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import java.util.Locale

data class ParsedWordByWordLyrics(
    val wordByWordLines: List<WordByWordLine>,
    val plainLrc: String,
    val hasTranslation: Boolean,
    val metadataLines: List<String> = emptyList()
)

object WordByWordLrcParser {
    /** Closed square-tag lines distinguish verbatim LRC from repeated timestamps and compact LRC. */
    fun hasWordTiming(text: String): Boolean = text.lineSequence().any {
        wordTagCandidateRegex.containsMatchIn(it) || isSquareWordLine(it.trim())
    }

    data class StorageValidationResult(
        val isValid: Boolean,
        val invalidLineNumbers: List<Int> = emptyList()
    )

    fun parse(wordByWordLrc: String): List<WordByWordLine> {
        return parseImport(wordByWordLrc).wordByWordLines
    }

    fun parseImport(wordByWordLrc: String): ParsedWordByWordLyrics {
        if (wordByWordLrc.isBlank()) {
            return emptyParsedWordByWordLyrics()
        }

        val importParts = collectImportParts(wordByWordLrc)
        val sortedRawLines = importParts.rawLines.sortedBy { it.startMs }
        if (sortedRawLines.isEmpty()) {
            return emptyParsedWordByWordLyrics(importParts.metadataLines)
        }

        val wordByWordLines = buildWordByWordLines(sortedRawLines)
        if (wordByWordLines.isEmpty()) {
            return emptyParsedWordByWordLyrics(importParts.metadataLines)
        }

        val translationsByStartMs = groupTranslationsByStartMs(
            rawLines = sortedRawLines,
            translationCandidates = importParts.translationCandidates
        )
        val effectiveTranslations = buildEffectiveTranslations(wordByWordLines, translationsByStartMs)

        return ParsedWordByWordLyrics(
            wordByWordLines = wordByWordLines,
            plainLrc = wordByWordLinesToPlainLrc(wordByWordLines, importParts.metadataLines, effectiveTranslations),
            hasTranslation = effectiveTranslations.isNotEmpty(),
            metadataLines = importParts.metadataLines
        )
    }

    fun validateForStorage(wordByWordLrc: String): StorageValidationResult {
        val invalidLineNumbers = wordByWordLrc
            .lineSequence()
            .mapIndexedNotNull { index, rawLine ->
                val line = rawLine.trim()
                if (line.isBlank() || parseMetadataLine(line) != null) {
                    return@mapIndexedNotNull null
                }
                if (isValidWordByWordLrcImportLine(line)) null else index + 1
            }
            .toList()

        if (invalidLineNumbers.isNotEmpty()) {
            return StorageValidationResult(isValid = false, invalidLineNumbers = invalidLineNumbers)
        }

        return StorageValidationResult(isValid = parse(wordByWordLrc).isNotEmpty())
    }

    fun wordByWordLinesToWordByWordLrc(
        wordByWordLines: List<WordByWordLine>,
        metadataLines: List<String> = emptyList()
    ): String {
        val wordByWordLrcLines = wordByWordLines
            .sortedBy { it.startMs }
            .map { line ->
                val segments = line.segments.sortedBy { it.startMs }
                val segmentText = segments.mapIndexed { index, segment ->
                    val endTag = if (segments.getOrNull(index + 1)?.startMs == segment.endMs) ""
                        else "<${formatLrcTimeTag(segment.endMs)}>"
                    "<${formatLrcTimeTag(segment.startMs)}>${segment.text}$endTag"
                }.joinToString("")
                "[${formatLrcTimeTag(line.startMs)}]$segmentText"
            }

        return joinMetadataAndLyricLines(metadataLines, wordByWordLrcLines)
    }

    fun wordByWordLinesToPlainLrc(
        wordByWordLines: List<WordByWordLine>,
        metadataLines: List<String> = emptyList()
    ): String {
        return wordByWordLinesToPlainLrc(wordByWordLines, metadataLines, emptyMap())
    }

    fun wordByWordLinesToPlainLrc(
        wordByWordLines: List<WordByWordLine>,
        metadataLines: List<String>,
        translationsByStartMs: Map<Long, List<String>>
    ): String {
        return formatPlainLrc(wordByWordLines, metadataLines, translationsByStartMs)
    }
}

private const val FALLBACK_SEGMENT_DURATION_MS = 400L

private data class RawWordByWordLine(
    val startMs: Long,
    val segments: List<RawWordSegment>
)

private data class RawWordSegment(val text: String, val startMs: Long, val endMs: Long?)

private data class WordByWordImportParts(
    val metadataLines: List<String>,
    val rawLines: List<RawWordByWordLine>,
    val translationCandidates: List<TimedTextSegment>
)

private data class TimedTextSegment(
    val startMs: Long,
    val text: String
)

private fun emptyParsedWordByWordLyrics(
    metadataLines: List<String> = emptyList()
): ParsedWordByWordLyrics {
    return ParsedWordByWordLyrics(
        emptyList(),
        plainLrc = "",
        hasTranslation = false,
        metadataLines = metadataLines
    )
}

private fun collectImportParts(wordByWordLrc: String): WordByWordImportParts {
    val metadataLines = mutableListOf<String>()
    val rawLines = mutableListOf<RawWordByWordLine>()
    val originalStarts = mutableSetOf<Long>()
    val translationCandidates = mutableListOf<TimedTextSegment>()

    wordByWordLrc.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isBlank()) return@forEach

        parseMetadataLine(line)?.let {
            metadataLines += it
            return@forEach
        }

        val rawWordByWordLine = parseRawWordByWordLine(line)
        if (rawWordByWordLine != null) {
            // LDDC emits original, translation and romanization in that order at the same start.
            if (!originalStarts.add(rawWordByWordLine.startMs)) {
                translationCandidates += TimedTextSegment(rawWordByWordLine.startMs,
                    rawWordByWordLine.segments.joinToString("") { it.text })
            } else rawLines += rawWordByWordLine
            return@forEach
        }

        val translation = terminalOnlyTranslation(line)
        if (translation != null) {
            translationCandidates += translation
        } else if (!wordTagCandidateRegex.containsMatchIn(line)) {
            translationCandidates += parseTimedTextSegments(line)
        }
    }

    return WordByWordImportParts(
        metadataLines = metadataLines,
        rawLines = rawLines,
        translationCandidates = translationCandidates
    )
}

private fun buildWordByWordLines(
    sortedRawLines: List<RawWordByWordLine>
): List<WordByWordLine> {
    return sortedRawLines.mapIndexedNotNull { index, rawLine ->
        val nextLineStart = sortedRawLines.getOrNull(index + 1)?.startMs
        buildWordByWordLine(rawLine, nextLineStart)
    }
}

private fun buildWordByWordLine(
    rawLine: RawWordByWordLine,
    nextLineStart: Long?
): WordByWordLine? {
    val segmentList = buildSegmentList(rawLine, nextLineStart)
    val lineText = segmentList.joinToString(separator = "") { it.text }
        .replace(whitespaceRegex, " ")
        .trim()
    if (lineText.isBlank() || segmentList.isEmpty()) return null

    val lineEnd = segmentList.last().endMs
    if (lineEnd <= rawLine.startMs) return null

    return WordByWordLine(
        startMs = rawLine.startMs,
        endMs = lineEnd,
        text = lineText,
        segments = segmentList
    )
}

private fun buildSegmentList(
    rawLine: RawWordByWordLine,
    nextLineStart: Long?
): List<WordByWordSegment> {
    return rawLine.segments.mapIndexed { index, segment ->
        val end = segment.endMs ?: rawLine.segments.getOrNull(index + 1)?.startMs
            ?: nextLineStart ?: (segment.startMs + FALLBACK_SEGMENT_DURATION_MS)
        WordByWordSegment(segment.text, segment.startMs, end.coerceAtLeast(segment.startMs + 1L))
    }
}

private fun groupTranslationsByStartMs(
    rawLines: List<RawWordByWordLine>,
    translationCandidates: List<TimedTextSegment>
): Map<Long, List<String>> {
    val wordByWordStartTimes = rawLines.map { it.startMs }.toSet()
    return translationCandidates
        .filter { it.startMs in wordByWordStartTimes }
        .groupBy(
            keySelector = { it.startMs },
            valueTransform = { it.text }
        )
        .mapValues { (_, values) -> values.distinct() }
}

private fun buildEffectiveTranslations(
    wordByWordLines: List<WordByWordLine>,
    translationsByStartMs: Map<Long, List<String>>
): Map<Long, List<String>> {
    return translationsByStartMs
        .mapValues { (startMs, translations) ->
            val originalText = wordByWordLines.firstOrNull { it.startMs == startMs }?.text.orEmpty()
            cleanTranslationParts(translations, originalText)
        }
        .filterValues { it.isNotEmpty() }
}

private fun isValidWordByWordLrcImportLine(line: String): Boolean {
    if (parseRawWordByWordLine(line) != null || terminalOnlyTranslation(line) != null) return true
    if (wordTagCandidateRegex.containsMatchIn(line) || isSquareWordLine(line)) return false
    return parseTimedTextSegments(line).isNotEmpty()
}

private fun isSquareWordLine(line: String): Boolean {
    val tags = timeTagRegex.findAll(line).toList()
    if (tags.size < 2 || tags.first().range.first != 0 || tags.last().range.last != line.lastIndex) return false
    // [t1][t2]same lyric is ordinary repeated-line LRC, not word timing.
    return line.substring(tags[0].range.last + 1, tags[1].range.first).isNotBlank()
}

/** LDDC can append only an end marker to an untimed translation. */
private fun terminalOnlyTranslation(line: String): TimedTextSegment? {
    val start = timeTagRegex.find(line)?.takeIf { it.range.first == 0 } ?: return null
    val tags = wordTimeTagRegex.findAll(line).toList()
    val end = tags.singleOrNull()?.takeIf { it.range.last == line.lastIndex } ?: return null
    val text = line.substring(start.range.last + 1, end.range.first)
    if (text.isBlank() || wordTagCandidateRegex.containsMatchIn(text)) return null
    val startMs = parseTimeTag(start) ?: return null
    val endMs = parseTimeTag(end) ?: return null
    if (endMs < startMs) return null
    return TimedTextSegment(startMs, text)
}

private fun parseRawWordByWordLine(line: String): RawWordByWordLine? {
    val lineTag = timeTagRegex.find(line)?.takeIf { it.range.first == 0 } ?: return null
    val lineStart = parseTimeTag(lineTag) ?: return null
    val enhanced = wordTagCandidateRegex.containsMatchIn(line)
    if (!enhanced && !isSquareWordLine(line)) return null
    val content = if (enhanced) line.substring(lineTag.range.last + 1) else line
    val tags = (if (enhanced) wordTimeTagRegex else timeTagRegex).findAll(content).toList()
    if (tags.isEmpty() || content.substring(0, tags.first().range.first).isNotBlank()) return null
    val times = tags.map { parseTimeTag(it) ?: return null }
    if (times.any { it < lineStart } || times.zipWithNext().any { (a, b) -> b < a }) return null
    val segments = tags.mapIndexedNotNull { index, tag ->
        val next = tags.getOrNull(index + 1)
        val text = content.substring(tag.range.last + 1, next?.range?.first ?: content.length)
        if (text.isBlank()) return@mapIndexedNotNull null
        // Do not hide malformed timing tags inside the lyric text.
        if (wordTagCandidateRegex.containsMatchIn(text) || squareTagCandidateRegex.containsMatchIn(text)) return null
        val end = times.getOrNull(index + 1)
        if (end != null && end <= times[index]) return null
        RawWordSegment(text, times[index], end)
    }
    return segments.takeIf { it.isNotEmpty() }?.let { RawWordByWordLine(lineStart, it) }
}

private fun parseTimedTextSegments(line: String): List<TimedTextSegment> {
    val matches = timeTagRegex.findAll(line).toList()
    if (matches.isEmpty()) return emptyList()

    val segments = mutableListOf<TimedTextSegment>()
    val pendingTimeTags = mutableListOf<MatchResult>()

    matches.forEachIndexed { index, match ->
        pendingTimeTags += match

        val segmentStart = match.range.last + 1
        val segmentEnd = matches.getOrNull(index + 1)?.range?.first ?: line.length
        if (segmentStart > segmentEnd) return@forEachIndexed

        val text = normalizeTimedText(line.substring(segmentStart, segmentEnd).trim())
        if (text.isBlank()) return@forEachIndexed

        pendingTimeTags.mapNotNullTo(segments) { pendingMatch ->
            val timeMs = parseTimeTag(pendingMatch) ?: return@mapNotNullTo null
            TimedTextSegment(timeMs, text)
        }
        pendingTimeTags.clear()
    }

    return segments
}

private fun formatPlainLrc(
    wordByWordLines: List<WordByWordLine>,
    metadataLines: List<String>,
    translationsByStartMs: Map<Long, List<String>>
): String {
    val plainLrcLines = wordByWordLines
        .sortedBy { it.startMs }
        .map { line ->
            val originalText = line.text.trim()
            val translation = translationsByStartMs[line.startMs]
                .orEmpty()
                .let { cleanTranslationParts(it, originalText) }
                .joinToString(" / ")
            val storedText = if (translation.isNotBlank()) {
                "$originalText / $translation"
            } else {
                originalText
            }
            "[${formatLrcTimeTag(line.startMs)}]$storedText"
        }

    return joinMetadataAndLyricLines(metadataLines, plainLrcLines)
}

private fun joinMetadataAndLyricLines(
    metadataLines: List<String>,
    formattedLrcLines: List<String>
): String {
    return (formatMetadataForStorage(metadataLines) + formattedLrcLines)
        .joinToString("\n")
}

private fun formatMetadataForStorage(metadataLines: List<String>): List<String> {
    return metadataLines
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

private fun splitTranslationParts(text: String): List<String> {
    return normalizeTimedText(text)
        .lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

private fun cleanTranslationParts(
    translations: List<String>,
    originalText: String
): List<String> {
    return translations
        .asSequence()
        .flatMap { splitTranslationParts(it).asSequence() }
        .map { it.trim() }
        .filter { it.isNotBlank() && it != originalText }
        .distinct()
        .toList()
}

private fun normalizeTimedText(text: String): String {
    return text
        .replace(" / ", "\n")
        .replace("／", "\n")
        .lines()
        .joinToString("\n") { it.trim() }
        .trim()
}

private fun parseMetadataLine(line: String): String? {
    return line.trim().takeIf { metadataTagRegex.matches(it) }
}

private fun formatLrcTimeTag(timeMs: Long): String {
    val minutes = timeMs / 60_000L
    val seconds = (timeMs % 60_000L) / 1_000L
    val centiseconds = (timeMs % 1_000L) / 10L
    return if (timeMs % 10L == 0L) "%02d:%02d.%02d".format(Locale.ROOT, minutes, seconds, centiseconds)
    else "%02d:%02d.%03d".format(Locale.ROOT, minutes, seconds, timeMs % 1_000L)
}

private fun parseTimeTag(match: MatchResult): Long? {
    val minutes = match.groupValues.getOrNull(1)?.toLongOrNull() ?: return null
    val seconds = match.groupValues.getOrNull(2)?.toLongOrNull() ?: return null
    if (seconds >= 60) return null
    val fractionRaw = match.groupValues.getOrNull(3).orEmpty()
    val millis = when (fractionRaw.length) {
        0 -> 0L
        1 -> fractionRaw.toLongOrNull()?.times(100L) ?: return null
        2 -> fractionRaw.toLongOrNull()?.times(10L) ?: return null
        else -> fractionRaw.take(3).toLongOrNull() ?: return null
    }
    return minutes * 60_000L + seconds * 1_000L + millis
}

private val timeTagRegex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
private val wordTimeTagRegex = Regex("""<(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?>""")
private val metadataTagRegex = Regex("""\[[A-Za-z][A-Za-z0-9_\-]*:.*]""")
private val whitespaceRegex = Regex("\\s+")

private val wordTagCandidateRegex = Regex("""<\d{1,3}:""")
private val squareTagCandidateRegex = Regex("""\[\d{1,3}:""")
