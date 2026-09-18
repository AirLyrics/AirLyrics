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
    fun parse(plainLrc: String): List<LrcLine> {
        return parsePlainLines(plainLrc)
    }

    fun parseWithTranslation(plainLrc: String, translatedLrc: String?): List<LrcLine> {
        val originalLines = parsePlainLines(plainLrc)
        val translationLines = parsePlainLines(translatedLrc.orEmpty())

        if (translationLines.isEmpty()) return originalLines
        if (originalLines.isEmpty()) return translationLines.asTranslatedOnlyLines()

        return TranslationMatcher(originalLines, translationLines).merge()
    }

    /**
     * Builds a legacy single-LRC payload that keeps translations readable in older local caches.
     * The runtime display path should prefer [parseWithTranslation], but saved .lrc files still
     * need to be useful when read as plain LRC later.
     */
    fun mergeOriginalAndTranslationForStorage(plainLrc: String, translatedLrc: String?): String {
        if (translatedLrc.isNullOrBlank()) return plainLrc

        val mergedLines = parseWithTranslation(plainLrc, translatedLrc)
        if (mergedLines.isEmpty()) return plainLrc.ifBlank { translatedLrc }

        return formatLinesForStorage(mergedLines)
    }

    /**
     * Converts user-imported ordinary LRC into AirLyrics' preferred storage format.
     * The importer still accepts common variants such as [00:12:34] and compact
     * one-line exports, but the managed local cache is saved as one lyric line per
     * row using [mm:ss.xx] text.
     */
    fun normalizeForStorage(plainLrc: String): String {
        return formatLinesForStorage(
            parsePlainLines(plainLrc, mergeSameTimestampTranslations = true)
        )
    }

    data class StorageValidationResult(
        val isValid: Boolean,
        val invalidLineNumbers: List<Int> = emptyList()
    )

    fun validateForStorage(plainLrc: String): StorageValidationResult {
        val invalidLineNumbers = plainLrc
            .lineSequence()
            .mapIndexedNotNull { index, rawLine ->
                if (isIgnorableStorageLine(rawLine)) return@mapIndexedNotNull null
                if (parseTimedSegments(rawLine).isEmpty()) index + 1 else null
            }
            .toList()

        if (invalidLineNumbers.isNotEmpty()) {
            return StorageValidationResult(isValid = false, invalidLineNumbers = invalidLineNumbers)
        }

        val hasLyricLine = parsePlainLines(
            plainLrc,
            mergeSameTimestampTranslations = true
        ).any { line ->
            !line.isMetadata && (line.text.isNotBlank() || line.hasTranslation())
        }

        return StorageValidationResult(isValid = hasLyricLine)
    }

    fun findCurrentIndex(plainLines: List<LrcLine>, positionMs: Long): Int? {
        if (plainLines.isEmpty()) return null

        var left = 0
        var right = plainLines.lastIndex
        var result: Int? = null

        while (left <= right) {
            val mid = (left + right) / 2
            val plainLine = plainLines[mid]

            if (plainLine.timeMs <= positionMs) {
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
private val translationOnlyPrefixRegex = Regex("""^[／/]\s+(.+)$""")
private val keyValueLineRegex = Regex("""^[^\n]{1,32}\s*[:：]""")
private val lyricsCreditLineRegex = Regex(
    "^(?:" +
        "\u4F5C\u8BCD|\u4F5C\u8A5E|\u586B\u8BCD|\u4F5C\u66F2|" +
        "\u7F16\u66F2|\u7DE8\u66F2|\u8BD1\u8BCD|\u7FFB\u8BD1|\u7FFB\u8A33|" +
        "\u4F5C\u8BCD\u4EBA|\u4F5C\u8A5E\u5BB6|\u4F5C\u66F2\u4EBA|\u5236\u4F5C\u4EBA|" +
        "\u76D1\u5236|\u76E3\u88FD|\uC791\uC0AC|\uC791\uACE1|\uD3B8\uACE1|\uBC88\uC5ED|" +
        "(?i:lyrics?|lyricist|composer|composition|arranger|translation|translator|producer)" +
        ")\\s*[:\uFF1A]"
)
private const val TRANSLATION_MATCH_TOLERANCE_MS = 500L
private const val UNMATCHED_TRANSLATION_ATTACH_TOLERANCE_MS = 2_000L
private const val METADATA_DISPLAY_TIME_MS = 0L

private fun parsePlainLines(
    plainLrc: String,
    mergeSameTimestampTranslations: Boolean = false
): List<LrcLine> {
    val timedLines = mutableListOf<LrcLine>()
    val metadataLines = mutableListOf<String>()

    plainLrc.lineSequence().forEach { rawLine ->
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
            line.copy(text = "", translation = line.translationText())
        }
    }
}

private class TranslationMatcher(
    private val originalLines: List<LrcLine>,
    private val translationLines: List<LrcLine>
) {
    fun merge(): List<LrcLine> {
        val matchedTranslations = Array(originalLines.size) {
            mutableListOf<MatchedTranslation>()
        }
        val usedOriginalIndexes = BooleanArray(originalLines.size)
        val usedTranslationIndexes = BooleanArray(translationLines.size)
        val originalIndexes = originalLines.indices.filter { index ->
            originalLines[index].isEligibleOriginalLine()
        }
        val translationIndexes = translationLines.indices.filter { index ->
            translationLines[index].isEligibleTranslationLine()
        }

        if (originalIndexes.isNotEmpty() && originalIndexes.size == translationIndexes.size) {
            // Separate translation tracks normally preserve lyric order even when every
            // timestamp has the same provider-specific offset. Pair the complete tracks
            // before considering distance so matches can never cross.
            originalIndexes.zip(translationIndexes).forEach { (originalIndex, translationIndex) ->
                matchTranslation(
                    originalIndex = originalIndex,
                    translationIndex = translationIndex,
                    matchedTranslations = matchedTranslations,
                    usedOriginalIndexes = usedOriginalIndexes,
                    usedTranslationIndexes = usedTranslationIndexes
                )
            }
        } else {
            buildMonotonicMatches(originalIndexes, translationIndexes).forEach { candidate ->
                matchTranslation(
                    originalIndex = candidate.originalIndex,
                    translationIndex = candidate.translationIndex,
                    matchedTranslations = matchedTranslations,
                    usedOriginalIndexes = usedOriginalIndexes,
                    usedTranslationIndexes = usedTranslationIndexes
                )
            }
        }

        attachNearbyUnmatchedTranslations(
            originalIndexes = originalIndexes,
            translationIndexes = translationIndexes,
            matchedTranslations = matchedTranslations,
            usedOriginalIndexes = usedOriginalIndexes,
            usedTranslationIndexes = usedTranslationIndexes
        )

        return originalLines.mapIndexed { index, original ->
            val translation = matchedTranslations[index]
                .sortedBy(MatchedTranslation::translationIndex)
                .joinToString("\n", transform = MatchedTranslation::text)
                .takeIf { it.isNotBlank() && it.trim() != original.text.trim() }

            original.copy(translation = translation ?: original.translation)
        }
    }

    private fun matchTranslation(
        originalIndex: Int,
        translationIndex: Int,
        matchedTranslations: Array<MutableList<MatchedTranslation>>,
        usedOriginalIndexes: BooleanArray,
        usedTranslationIndexes: BooleanArray
    ) {
        val originalText = originalLines[originalIndex].text.trim()
        val translation = translationLines[translationIndex]
            .translationText()
            .withoutOriginalText(originalText)
        if (translation.isNotBlank()) {
            matchedTranslations[originalIndex] += MatchedTranslation(
                translationIndex = translationIndex,
                text = translation
            )
        }
        usedOriginalIndexes[originalIndex] = true
        usedTranslationIndexes[translationIndex] = true
    }

    private fun attachNearbyUnmatchedTranslations(
        originalIndexes: List<Int>,
        translationIndexes: List<Int>,
        matchedTranslations: Array<MutableList<MatchedTranslation>>,
        usedOriginalIndexes: BooleanArray,
        usedTranslationIndexes: BooleanArray
    ) {
        if (originalIndexes.isEmpty()) return

        translationIndexes.forEach { translationIndex ->
            if (usedTranslationIndexes[translationIndex]) return@forEach

            val translationLine = translationLines[translationIndex]
            val originalIndex = originalIndexes.minWithOrNull(
                compareBy<Int> { index ->
                    abs(originalLines[index].timeMs - translationLine.timeMs)
                }.thenBy { index -> if (usedOriginalIndexes[index]) 1 else 0 }
            ) ?: return@forEach
            val distanceMs = abs(originalLines[originalIndex].timeMs - translationLine.timeMs)
            if (distanceMs > UNMATCHED_TRANSLATION_ATTACH_TOLERANCE_MS) return@forEach

            val translation = translationLine
                .translationText()
                .withoutOriginalText(originalLines[originalIndex].text.trim())
            if (translation.isNotBlank()) {
                matchedTranslations[originalIndex] += MatchedTranslation(
                    translationIndex = translationIndex,
                    text = translation
                )
            }
            usedOriginalIndexes[originalIndex] = true
            usedTranslationIndexes[translationIndex] = true
        }
    }

    private fun buildMonotonicMatches(
        originalIndexes: List<Int>,
        translationIndexes: List<Int>
    ): List<TranslationMatch> {
        if (originalIndexes.isEmpty() || translationIndexes.isEmpty()) return emptyList()

        val originalCount = originalIndexes.size
        val translationCount = translationIndexes.size
        val decisions = Array(originalCount + 1) { ByteArray(translationCount + 1) }
        var previousMatchCounts = IntArray(translationCount + 1)
        var previousTotalDistances = LongArray(translationCount + 1)
        var currentMatchCounts = IntArray(translationCount + 1)
        var currentTotalDistances = LongArray(translationCount + 1)

        for (originalPosition in 1..originalCount) {
            currentMatchCounts[0] = 0
            currentTotalDistances[0] = 0L
            for (translationPosition in 1..translationCount) {
                var bestMatches = previousMatchCounts[translationPosition]
                var bestDistance = previousTotalDistances[translationPosition]
                var bestDecision = ALIGNMENT_SKIP_ORIGINAL

                val skipTranslationMatches = currentMatchCounts[translationPosition - 1]
                val skipTranslationDistance = currentTotalDistances[translationPosition - 1]
                if (
                    isBetterAlignment(
                        candidateMatches = skipTranslationMatches,
                        candidateDistance = skipTranslationDistance,
                        currentMatches = bestMatches,
                        currentDistance = bestDistance
                    )
                ) {
                    bestMatches = skipTranslationMatches
                    bestDistance = skipTranslationDistance
                    bestDecision = ALIGNMENT_SKIP_TRANSLATION
                }

                val originalIndex = originalIndexes[originalPosition - 1]
                val translationIndex = translationIndexes[translationPosition - 1]
                val distanceMs = abs(
                    originalLines[originalIndex].timeMs - translationLines[translationIndex].timeMs
                )
                if (distanceMs <= TRANSLATION_MATCH_TOLERANCE_MS) {
                    val matchedCount = previousMatchCounts[translationPosition - 1] + 1
                    val matchedDistance =
                        previousTotalDistances[translationPosition - 1] + distanceMs
                    if (
                        isBetterAlignment(
                            candidateMatches = matchedCount,
                            candidateDistance = matchedDistance,
                            currentMatches = bestMatches,
                            currentDistance = bestDistance
                        ) || (matchedCount == bestMatches && matchedDistance == bestDistance)
                    ) {
                        bestMatches = matchedCount
                        bestDistance = matchedDistance
                        bestDecision = ALIGNMENT_MATCH
                    }
                }

                currentMatchCounts[translationPosition] = bestMatches
                currentTotalDistances[translationPosition] = bestDistance
                decisions[originalPosition][translationPosition] = bestDecision
            }

            val completedMatchCounts = currentMatchCounts
            currentMatchCounts = previousMatchCounts
            previousMatchCounts = completedMatchCounts

            val completedTotalDistances = currentTotalDistances
            currentTotalDistances = previousTotalDistances
            previousTotalDistances = completedTotalDistances
        }

        val matches = mutableListOf<TranslationMatch>()
        var originalPosition = originalCount
        var translationPosition = translationCount
        while (originalPosition > 0 && translationPosition > 0) {
            when (decisions[originalPosition][translationPosition]) {
                ALIGNMENT_MATCH -> {
                    val originalIndex = originalIndexes[originalPosition - 1]
                    val translationIndex = translationIndexes[translationPosition - 1]
                    matches += TranslationMatch(
                        originalIndex = originalIndex,
                        translationIndex = translationIndex
                    )
                    originalPosition--
                    translationPosition--
                }
                ALIGNMENT_SKIP_TRANSLATION -> translationPosition--
                else -> originalPosition--
            }
        }

        matches.reverse()
        return matches
    }
}

private data class TranslationMatch(
    val originalIndex: Int,
    val translationIndex: Int
)

private data class MatchedTranslation(
    val translationIndex: Int,
    val text: String
)

private fun isBetterAlignment(
    candidateMatches: Int,
    candidateDistance: Long,
    currentMatches: Int,
    currentDistance: Long
): Boolean {
    return candidateMatches > currentMatches ||
        (candidateMatches == currentMatches && candidateDistance < currentDistance)
}

private const val ALIGNMENT_SKIP_ORIGINAL: Byte = 1
private const val ALIGNMENT_SKIP_TRANSLATION: Byte = 2
private const val ALIGNMENT_MATCH: Byte = 3

private fun LrcLine.translationText(): String {
    return sequenceOf(text, translation.orEmpty())
        .flatMap { it.lineSequence() }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")
}

private fun LrcLine.isEligibleOriginalLine(): Boolean {
    return !isMetadata && text.isNotBlank() && !text.looksLikeLyricsCreditLine()
}

private fun LrcLine.isEligibleTranslationLine(): Boolean {
    val value = translationText()
    return !isMetadata && value.isNotBlank() && !value.looksLikeLyricsCreditLine()
}

private fun String.withoutOriginalText(originalText: String): String {
    return lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && it != originalText }
        .joinToString("\n")
}

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
        line.hasTranslation() -> "/ ${formatTranslationForStorage(line.translation)}"
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
    return !isMetadata && text.isNotBlank() && !text.looksLikeKeyValueLine()
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

    fun flushWaitingTags(text: String, translation: String? = null) {
        tagsWaitingForText.mapNotNullTo(parsedLines) { pendingTag ->
            val timeMs = parseTimeTag(pendingTag) ?: return@mapNotNullTo null
            LrcLine(timeMs, text, translation)
        }
        tagsWaitingForText.clear()
    }

    timeTags.forEachIndexed { index, timeTag ->
        tagsWaitingForText += timeTag

        val rawText = readRawTextUntilNextTag(line, timeTag, timeTags.getOrNull(index + 1))
        if (rawText.isBlank()) return@forEachIndexed

        val text = normalizeDisplayText(rawText)
        val (originalText, translationText) = splitOriginalAndTranslation(text)
        flushWaitingTags(originalText, translationText)
    }

    flushWaitingTags(text = "")

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
    translationOnlyPrefixRegex.matchEntire(text.trim())?.let { match ->
        val translation = inlineTranslationSeparatorRegex
            .split(match.groupValues[1])
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
        return "" to translation.takeIf { it.isNotBlank() }
    }

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

private fun String.looksLikeLyricsCreditLine(): Boolean {
    return lyricsCreditLineRegex.containsMatchIn(trim())
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
