package com.andsi.airlyrics.lyrics.parser

import com.andsi.airlyrics.lyrics.KaraokeLine
import com.andsi.airlyrics.lyrics.KaraokeToken
import java.util.Locale

data class ParsedKaraokeImport(
    val karaokeLines: List<KaraokeLine>,
    val plainLrc: String,
    val hasTranslation: Boolean,
    val metadataLines: List<String> = emptyList()
)

object KaraokeLrcParser {
    data class StorageValidationResult(
        val isValid: Boolean,
        val invalidLineNumbers: List<Int> = emptyList()
    )

    fun parse(rawLrc: String): List<KaraokeLine> {
        return parseImport(rawLrc).karaokeLines
    }

    fun parseImport(rawLrc: String): ParsedKaraokeImport {
        if (rawLrc.isBlank()) {
            return emptyKaraokeImport()
        }

        val importParts = collectImportParts(rawLrc)
        val sortedRawLines = importParts.rawLines.sortedBy { it.startMs }
        if (sortedRawLines.isEmpty()) {
            return emptyKaraokeImport(importParts.metadataLines)
        }

        val karaokeLines = buildKaraokeLines(sortedRawLines)
        if (karaokeLines.isEmpty()) {
            return emptyKaraokeImport(importParts.metadataLines)
        }

        val translationsByStartMs = groupTranslationsByStartMs(
            rawLines = sortedRawLines,
            translationCandidates = importParts.translationCandidates
        )
        val effectiveTranslations = buildEffectiveTranslations(karaokeLines, translationsByStartMs)

        return ParsedKaraokeImport(
            karaokeLines = karaokeLines,
            plainLrc = linesToPlainLrc(karaokeLines, importParts.metadataLines, effectiveTranslations),
            hasTranslation = effectiveTranslations.isNotEmpty(),
            metadataLines = importParts.metadataLines
        )
    }

    fun validateForStorage(rawLrc: String): StorageValidationResult {
        val invalidLineNumbers = rawLrc
            .lineSequence()
            .mapIndexedNotNull { index, rawLine ->
                val line = rawLine.trim()
                if (line.isBlank() || parseMetadataLine(line) != null) {
                    return@mapIndexedNotNull null
                }
                if (isValidEnhancedLrcImportLine(line)) null else index + 1
            }
            .toList()

        if (invalidLineNumbers.isNotEmpty()) {
            return StorageValidationResult(isValid = false, invalidLineNumbers = invalidLineNumbers)
        }

        return StorageValidationResult(isValid = parse(rawLrc).isNotEmpty())
    }

    fun linesToEnhancedLrc(
        lines: List<KaraokeLine>,
        metadataLines: List<String> = emptyList()
    ): String {
        val lyricLines = lines
            .sortedBy { it.startMs }
            .map { line ->
                val tokenText = line.tokens
                    .sortedBy { it.startMs }
                    .joinToString(separator = "") { token ->
                        "<${formatLrcTimeTag(token.startMs)}>${token.text}"
                    }
                "[${formatLrcTimeTag(line.startMs)}]$tokenText"
            }

        return joinMetadataAndLyricLines(metadataLines, lyricLines)
    }

    fun linesToPlainLrc(
        lines: List<KaraokeLine>,
        metadataLines: List<String> = emptyList()
    ): String {
        return linesToPlainLrc(lines, metadataLines, emptyMap())
    }
}

private const val FALLBACK_TOKEN_DURATION_MS = 400L

private data class RawEnhancedLine(
    val startMs: Long,
    val tokens: List<Pair<String, Long>>
)

private data class KaraokeImportParts(
    val metadataLines: List<String>,
    val rawLines: List<RawEnhancedLine>,
    val translationCandidates: List<TimedTextSegment>
)

private data class TimedTextSegment(
    val startMs: Long,
    val text: String
)

private fun emptyKaraokeImport(
    metadataLines: List<String> = emptyList()
): ParsedKaraokeImport {
    return ParsedKaraokeImport(
        emptyList(),
        plainLrc = "",
        hasTranslation = false,
        metadataLines = metadataLines
    )
}

private fun collectImportParts(rawLrc: String): KaraokeImportParts {
    val metadataLines = mutableListOf<String>()
    val rawLines = mutableListOf<RawEnhancedLine>()
    val translationCandidates = mutableListOf<TimedTextSegment>()

    rawLrc.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isBlank()) return@forEach

        parseMetadataLine(line)?.let {
            metadataLines += it
            return@forEach
        }

        val rawEnhancedLine = parseRawEnhancedLine(line)
        if (rawEnhancedLine != null) {
            rawLines += rawEnhancedLine
            return@forEach
        }

        if (wordTimeTagRegex.containsMatchIn(line)) return@forEach

        translationCandidates += parseTimedTextSegments(line)
    }

    return KaraokeImportParts(
        metadataLines = metadataLines,
        rawLines = rawLines,
        translationCandidates = translationCandidates
    )
}

private fun buildKaraokeLines(
    sortedRawLines: List<RawEnhancedLine>
): List<KaraokeLine> {
    return sortedRawLines.mapIndexedNotNull { index, rawLine ->
        val nextLineStart = sortedRawLines.getOrNull(index + 1)?.startMs
        buildKaraokeLine(rawLine, nextLineStart)
    }
}

private fun buildKaraokeLine(
    rawLine: RawEnhancedLine,
    nextLineStart: Long?
): KaraokeLine? {
    val tokenList = buildTokenList(rawLine, nextLineStart)
    val lineText = tokenList.joinToString(separator = "") { it.text }
        .replace(whitespaceRegex, " ")
        .trim()
    if (lineText.isBlank() || tokenList.isEmpty()) return null

    val lineEnd = nextLineStart ?: tokenList.last().endMs
    if (lineEnd <= rawLine.startMs) return null

    return KaraokeLine(
        startMs = rawLine.startMs,
        endMs = lineEnd,
        text = lineText,
        tokens = tokenList
    )
}

private fun buildTokenList(
    rawLine: RawEnhancedLine,
    nextLineStart: Long?
): List<KaraokeToken> {
    return rawLine.tokens.mapIndexed { tokenIndex, (tokenText, tokenStartMs) ->
        val nextTokenStart = rawLine.tokens.getOrNull(tokenIndex + 1)?.second
            ?: nextLineStart
            ?: (tokenStartMs + FALLBACK_TOKEN_DURATION_MS)
        KaraokeToken(
            text = tokenText,
            startMs = tokenStartMs,
            endMs = nextTokenStart.coerceAtLeast(tokenStartMs + 1L)
        )
    }
}

private fun groupTranslationsByStartMs(
    rawLines: List<RawEnhancedLine>,
    translationCandidates: List<TimedTextSegment>
): Map<Long, List<String>> {
    val karaokeStartTimes = rawLines.map { it.startMs }.toSet()
    return translationCandidates
        .filter { it.startMs in karaokeStartTimes }
        .groupBy(
            keySelector = { it.startMs },
            valueTransform = { it.text }
        )
        .mapValues { (_, values) -> values.distinct() }
}

private fun buildEffectiveTranslations(
    karaokeLines: List<KaraokeLine>,
    translationsByStartMs: Map<Long, List<String>>
): Map<Long, List<String>> {
    return translationsByStartMs
        .mapValues { (startMs, translations) ->
            val originalText = karaokeLines.firstOrNull { it.startMs == startMs }?.text.orEmpty()
            cleanTranslationParts(translations, originalText)
        }
        .filterValues { it.isNotEmpty() }
}

private fun isValidEnhancedLrcImportLine(line: String): Boolean {
    if (parseRawEnhancedLine(line) != null) return true
    if (wordTimeTagRegex.containsMatchIn(line)) return false
    return parseTimedTextSegments(line).isNotEmpty()
}

private fun parseRawEnhancedLine(line: String): RawEnhancedLine? {
    val lineStart = timeTagRegex.find(line)?.let { parseTimeTag(it) }
        ?: return null
    val content = line.replace(timeTagRegex, "").trim()
    val wordTags = wordTimeTagRegex.findAll(content).toList()
    if (wordTags.isEmpty()) return null

    val tokens = wordTags.mapIndexedNotNull { index, match ->
        val startMs = parseTimeTag(match) ?: return@mapIndexedNotNull null
        val textStart = match.range.last + 1
        val textEndExclusive = wordTags.getOrNull(index + 1)?.range?.first ?: content.length
        if (textStart > textEndExclusive || textStart > content.length) return@mapIndexedNotNull null
        val tokenText = content.substring(textStart, textEndExclusive)
            .replace(wordTimeTagRegex, "")
        if (tokenText.isBlank()) return@mapIndexedNotNull null
        tokenText to startMs
    }.filter { (_, startMs) -> startMs >= lineStart }

    return if (tokens.isEmpty()) null else RawEnhancedLine(lineStart, tokens)
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

private fun linesToPlainLrc(
    lines: List<KaraokeLine>,
    metadataLines: List<String>,
    translationsByStartMs: Map<Long, List<String>>
): String {
    val lyricLines = lines
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

    return joinMetadataAndLyricLines(metadataLines, lyricLines)
}

private fun joinMetadataAndLyricLines(
    metadataLines: List<String>,
    lyricLines: List<String>
): String {
    return (formatMetadataForStorage(metadataLines) + lyricLines)
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
