package com.andsi.airlyrics.lyrics.parser

import kotlin.math.abs

data class LrcLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
    val isMetadata: Boolean = false
) {
    fun hasTranslation(): Boolean = !translation.isNullOrBlank()
}

object LrcParser {
    fun parse(lyrics: String): List<LrcLine> {
        return parsePlainLines(lyrics)
    }

    fun parseWithTranslation(lyrics: String, translatedLyrics: String?): List<LrcLine> {
        val originalLines = parsePlainLines(lyrics)
        val translationLines = parsePlainLines(translatedLyrics.orEmpty())

        if (translationLines.isEmpty()) return originalLines
        if (originalLines.isEmpty()) return translationLines.asTranslatedOnlyLines()

        return TranslationMatcher(originalLines, translationLines).merge()
    }

    /**
     * Builds a legacy single-LRC payload that keeps translations readable in older local caches.
     * The runtime display path should prefer [parseWithTranslation], but saved .lrc files still
     * need to be useful when read as plain LRC later.
     */
    fun mergeOriginalAndTranslationForStorage(lyrics: String, translatedLyrics: String?): String {
        if (translatedLyrics.isNullOrBlank()) return lyrics
        if (lyrics.isBlank()) return translatedLyrics

        val mergedLines = parseWithTranslation(lyrics, translatedLyrics)
        if (mergedLines.isEmpty()) return lyrics

        return formatLinesForStorage(mergedLines)
    }

    /**
     * Converts user-imported ordinary LRC into AirLyrics' preferred storage format.
     * The importer still accepts common variants such as [00:12:34] and compact
     * one-line exports, but the managed local cache is saved as one lyric line per
     * row using [mm:ss.xx] text.
     */
    fun normalizeForStorage(lyrics: String): String {
        return formatLinesForStorage(
            parsePlainLines(lyrics, mergeSameTimestampTranslations = true)
        )
    }

    data class StorageValidationResult(
        val isValid: Boolean,
        val invalidLineNumbers: List<Int> = emptyList()
    )

    fun validateForStorage(lyrics: String): StorageValidationResult {
        val invalidLineNumbers = lyrics
            .lineSequence()
            .mapIndexedNotNull { index, rawLine ->
                if (isIgnorableStorageLine(rawLine)) return@mapIndexedNotNull null
                if (parseTimedSegments(rawLine).isEmpty()) index + 1 else null
            }
            .toList()

        if (invalidLineNumbers.isNotEmpty()) {
            return StorageValidationResult(isValid = false, invalidLineNumbers = invalidLineNumbers)
        }

        return StorageValidationResult(isValid = normalizeForStorage(lyrics).isNotBlank())
    }

    fun findCurrentIndex(lines: List<LrcLine>, positionMs: Long): Int? {
        if (lines.isEmpty()) return null

        var left = 0
        var right = lines.lastIndex
        var result: Int? = null

        while (left <= right) {
            val mid = (left + right) / 2
            val line = lines[mid]

            if (line.timeMs <= positionMs) {
                result = mid
                left = mid + 1
            } else {
                right = mid - 1
            }
        }

        return result
    }
}

private val timeTagRegex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
private val metadataTagRegex = Regex("""\[[A-Za-z][A-Za-z0-9_\-]*:.*]""")
private val inlineTranslationSeparatorRegex = Regex("""\s+/\s+|／""")
private val keyValueLineRegex = Regex("""^[^\n]{1,32}\s*[:：]""")
private const val TRANSLATION_MATCH_TOLERANCE_MS = 500L
private const val METADATA_DISPLAY_TIME_MS = 0L

private fun parsePlainLines(
    lyrics: String,
    mergeSameTimestampTranslations: Boolean = false
): List<LrcLine> {
    val timedLines = mutableListOf<LrcLine>()
    val metadataLines = mutableListOf<String>()

    lyrics.lineSequence().forEach { rawLine ->
        val timedSegments = parseTimedSegments(rawLine)
        if (timedSegments.isNotEmpty()) {
            timedLines += timedSegments
        } else {
            parseMetadataLine(rawLine)?.let { metadataLines += it }
        }
    }

    val sortedLines = buildList {
        buildMetadataDisplayLine(metadataLines)?.let { add(it) }
        addAll(timedLines.sortedBy { it.timeMs })
    }

    return if (mergeSameTimestampTranslations) {
        mergeSameTimestampLines(sortedLines)
    } else {
        sortedLines
    }
}

private fun List<LrcLine>.asTranslatedOnlyLines(): List<LrcLine> {
    return map { line ->
        if (line.isMetadata) {
            line
        } else {
            line.copy(text = "", translation = line.text)
        }
    }
}

private class TranslationMatcher(
    private val originalLines: List<LrcLine>,
    private val translationLines: List<LrcLine>
) {
    fun merge(): List<LrcLine> {
        val matchedTranslations = arrayOfNulls<String>(originalLines.size)
        val usedOriginalIndexes = BooleanArray(originalLines.size)
        val usedTranslationIndexes = BooleanArray(translationLines.size)

        buildCandidates().forEach { candidate ->
            if (usedOriginalIndexes[candidate.originalIndex]) return@forEach
            if (usedTranslationIndexes[candidate.translationIndex]) return@forEach

            usedOriginalIndexes[candidate.originalIndex] = true
            usedTranslationIndexes[candidate.translationIndex] = true
            matchedTranslations[candidate.originalIndex] = translationLines[candidate.translationIndex].text
        }

        return originalLines.mapIndexed { index, original ->
            val translation = matchedTranslations[index]
                ?.takeIf { it.isNotBlank() && it.trim() != original.text.trim() }

            original.copy(translation = translation ?: original.translation)
        }
    }

    private fun buildCandidates(): List<TranslationCandidate> {
        val candidates = mutableListOf<TranslationCandidate>()
        var firstTranslationCandidateIndex = 0

        originalLines.forEachIndexed { originalIndex, original ->
            if (original.isMetadata) return@forEachIndexed

            val earliestMatchTimeMs = original.timeMs - TRANSLATION_MATCH_TOLERANCE_MS
            while (
                firstTranslationCandidateIndex < translationLines.size &&
                translationLines[firstTranslationCandidateIndex].timeMs < earliestMatchTimeMs
            ) {
                firstTranslationCandidateIndex++
            }

            var translationIndex = firstTranslationCandidateIndex
            while (translationIndex < translationLines.size) {
                val translation = translationLines[translationIndex]
                if (translation.timeMs > original.timeMs + TRANSLATION_MATCH_TOLERANCE_MS) break

                if (!translation.isMetadata) {
                    candidates += TranslationCandidate(
                        originalIndex = originalIndex,
                        translationIndex = translationIndex,
                        distanceMs = abs(translation.timeMs - original.timeMs)
                    )
                }

                translationIndex++
            }
        }

        return candidates.sortedWith(
            compareBy<TranslationCandidate> { it.distanceMs }
                .thenBy { it.originalIndex }
                .thenBy { it.translationIndex }
        )
    }
}

private data class TranslationCandidate(
    val originalIndex: Int,
    val translationIndex: Int,
    val distanceMs: Long
)

private fun isIgnorableStorageLine(rawLine: String): Boolean {
    val line = rawLine.trim()
    return line.isBlank() || metadataTagRegex.matches(line)
}

private fun parseMetadataLine(rawLine: String): String? {
    val line = rawLine.trim()
    return line.takeIf { metadataTagRegex.matches(it) }
}

private fun buildMetadataDisplayLine(metadataLines: List<String>): LrcLine? {
    if (metadataLines.isEmpty()) return null

    return LrcLine(
        timeMs = METADATA_DISPLAY_TIME_MS,
        text = metadataLines.joinToString("\n"),
        isMetadata = true
    )
}

private fun formatLinesForStorage(lines: List<LrcLine>): String {
    return lines
        .filter { it.text.isNotBlank() || it.hasTranslation() }
        .joinToString("\n", transform = ::formatLineForStorage)
}

private fun formatLineForStorage(line: LrcLine): String {
    if (line.isMetadata && line.timeMs == METADATA_DISPLAY_TIME_MS) {
        return formatMetadataForStorage(line.text)
    }

    val text = when {
        line.text.isNotBlank() && line.hasTranslation() -> {
            "${line.text.trim()} / ${formatTranslationForStorage(line.translation)}"
        }
        line.text.isNotBlank() -> line.text.trim()
        line.hasTranslation() -> formatTranslationForStorage(line.translation)
        else -> ""
    }
    return "[${formatTimeTag(line.timeMs)}]$text"
}

private fun formatMetadataForStorage(text: String): String {
    return text
        .lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")
}

private fun mergeSameTimestampLines(sortedLines: List<LrcLine>): List<LrcLine> {
    if (sortedLines.size <= 1) return sortedLines

    val mergedLines = mutableListOf<LrcLine>()
    var groupStart = 0

    while (groupStart < sortedLines.size) {
        val timeMs = sortedLines[groupStart].timeMs
        var groupEnd = groupStart + 1

        while (groupEnd < sortedLines.size && sortedLines[groupEnd].timeMs == timeMs) {
            groupEnd++
        }

        mergedLines += mergeSameTimestampGroup(sortedLines.subList(groupStart, groupEnd))
        groupStart = groupEnd
    }

    return mergedLines
}

private fun mergeSameTimestampGroup(lines: List<LrcLine>): List<LrcLine> {
    if (lines.size == 1) return lines

    val mergeableLines = lines.filter { it.canMergeWithSameTimestampLyrics() }
    if (mergeableLines.size <= 1) return lines

    val mergedLine = mergeLyricTimestampGroup(mergeableLines)
    var insertedMergedLine = false

    return buildList {
        lines.forEach { line ->
            if (!line.canMergeWithSameTimestampLyrics()) {
                add(line)
            } else if (!insertedMergedLine) {
                add(mergedLine)
                insertedMergedLine = true
            }
        }
    }
}

private fun LrcLine.canMergeWithSameTimestampLyrics(): Boolean {
    return !isMetadata && !text.looksLikeKeyValueLine()
}

private fun mergeLyricTimestampGroup(lines: List<LrcLine>): LrcLine {
    if (lines.size == 1) return lines.single()

    val originalText = lines.first().text.trim()
    val translationParts = mutableListOf<String>()

    fun addTranslationPart(value: String?) {
        value.orEmpty()
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { it != originalText }
            .forEach { part ->
                if (part !in translationParts) translationParts += part
            }
    }

    addTranslationPart(lines.first().translation)
    lines.drop(1).forEach { line ->
        addTranslationPart(line.text)
        addTranslationPart(line.translation)
    }

    return lines.first().copy(
        text = originalText,
        translation = translationParts
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
    )
}

private fun formatTranslationForStorage(translation: String?): String {
    return translation.orEmpty()
        .lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(" / ")
}

/**
 * Parses both common LRC layouts:
 *
 * [00:01.00]first line
 * [00:02.00]second line
 *
 * and compact files exported as one physical line:
 *
 * [00:01:00]first line[00:02:00]second line
 *
 * Consecutive tags before one text segment are also kept compatible:
 * [00:01.00][00:02.00]shared text
 */
private fun parseTimedSegments(rawLine: String): List<LrcLine> {
    val line = rawLine.trim()
    if (line.isBlank()) return emptyList()

    val timeTags = timeTagRegex.findAll(line).toList()
    if (timeTags.isEmpty()) return emptyList()

    val parsedLines = mutableListOf<LrcLine>()
    val tagsWaitingForText = mutableListOf<MatchResult>()

    timeTags.forEachIndexed { index, timeTag ->
        tagsWaitingForText += timeTag

        val rawText = readRawTextUntilNextTag(line, timeTag, timeTags.getOrNull(index + 1))
        if (rawText.isBlank()) return@forEachIndexed

        val text = normalizeDisplayText(rawText)
        val (originalText, translationText) = splitOriginalAndTranslation(text)
        tagsWaitingForText.mapNotNullTo(parsedLines) { pendingTag ->
            val timeMs = parseTimeTag(pendingTag) ?: return@mapNotNullTo null
            LrcLine(timeMs, originalText, translationText)
        }
        tagsWaitingForText.clear()
    }

    return parsedLines
}

private fun readRawTextUntilNextTag(
    line: String,
    currentTag: MatchResult,
    nextTag: MatchResult?
): String {
    val segmentStart = currentTag.range.last + 1
    val segmentEnd = nextTag?.range?.first ?: line.length
    if (segmentStart > segmentEnd) return ""

    return line.substring(segmentStart, segmentEnd).trim()
}

private fun normalizeDisplayText(text: String): String {
    return text
        .lines()
        .joinToString("\n") { it.trim() }
        .trim()
}

private fun splitOriginalAndTranslation(text: String): Pair<String, String?> {
    val parts = text.lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }

    if (parts.size <= 1) {
        val inlineParts = splitInlineTranslation(text.trim())
            ?: return text.trim() to null

        return inlineParts.first() to inlineParts.drop(1).joinToString("\n")
    }

    return parts.first() to parts.drop(1).joinToString("\n").takeIf { it.isNotBlank() }
}

private fun splitInlineTranslation(text: String): List<String>? {
    if (text.looksLikeKeyValueLine()) return null

    val parts = inlineTranslationSeparatorRegex
        .split(text)
        .map { it.trim() }
        .filter { it.isNotBlank() }

    return parts.takeIf { it.size > 1 }
}

private fun String.looksLikeKeyValueLine(): Boolean {
    return keyValueLineRegex.containsMatchIn(trim())
}

private fun parseTimeTag(match: MatchResult): Long? {
    val minutes = match.groupValues[1].toLongOrNull() ?: return null
    val seconds = match.groupValues[2].toLongOrNull() ?: return null
    if (seconds >= 60) return null

    val fractionRaw = match.groupValues.getOrNull(3).orEmpty()
    val millis = when (fractionRaw.length) {
        0 -> 0L
        1 -> fractionRaw.toLong() * 100L
        2 -> fractionRaw.toLong() * 10L
        else -> fractionRaw.take(3).toLong()
    }

    return minutes * 60_000L + seconds * 1_000L + millis
}

private fun formatTimeTag(timeMs: Long): String {
    val minutes = timeMs / 60_000L
    val seconds = (timeMs % 60_000L) / 1_000L
    val centiseconds = (timeMs % 1_000L) / 10L
    return "%02d:%02d.%02d".format(minutes, seconds, centiseconds)
}
