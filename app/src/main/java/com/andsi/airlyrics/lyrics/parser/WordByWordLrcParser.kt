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
                val segmentText = line.segments
                    .sortedBy { it.startMs }
                    .joinToString(separator = "") { segment ->
                        "<${formatLrcTimeTag(segment.startMs)}>${segment.text}"
                    }
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
    val segments: List<Pair<String, Long>>
)

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
            rawLines += rawWordByWordLine
            return@forEach
        }

        if (wordTimeTagRegex.containsMatchIn(line)) return@forEach

        translationCandidates += parseTimedTextSegments(line)
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

    val lineEnd = nextLineStart ?: segmentList.last().endMs
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
    return rawLine.segments.mapIndexed { segmentIndex, (segmentText, segmentStartMs) ->
        val nextSegmentStart = rawLine.segments.getOrNull(segmentIndex + 1)?.second
            ?: nextLineStart
            ?: (segmentStartMs + FALLBACK_SEGMENT_DURATION_MS)
        WordByWordSegment(
            text = segmentText,
            startMs = segmentStartMs,
            endMs = nextSegmentStart.coerceAtLeast(segmentStartMs + 1L)
        )
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
    if (parseRawWordByWordLine(line) != null) return true
    if (wordTimeTagRegex.containsMatchIn(line)) return false
    return parseTimedTextSegments(line).isNotEmpty()
}

private fun parseRawWordByWordLine(line: String): RawWordByWordLine? {
    val lineStart = timeTagRegex.find(line)?.let { parseTimeTag(it) }
        ?: return null
    val content = line.replace(timeTagRegex, "").trim()
    val wordTags = wordTimeTagRegex.findAll(content).toList()
    if (wordTags.isEmpty()) return null

    val segments = wordTags.mapIndexedNotNull { index, match ->
        val startMs = parseTimeTag(match) ?: return@mapIndexedNotNull null
        val textStart = match.range.last + 1
        val textEndExclusive = wordTags.getOrNull(index + 1)?.range?.first ?: content.length
        if (textStart > textEndExclusive || textStart > content.length) return@mapIndexedNotNull null
        val segmentText = content.substring(textStart, textEndExclusive)
            .replace(wordTimeTagRegex, "")
        if (segmentText.isBlank()) return@mapIndexedNotNull null
        segmentText to startMs
    }.filter { (_, startMs) -> startMs >= lineStart }

    return if (segments.isEmpty()) null else RawWordByWordLine(lineStart, segments)
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
    return "%02d:%02d.%02d".format(Locale.ROOT, minutes, seconds, centiseconds)
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
