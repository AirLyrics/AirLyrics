package com.andsi.airlyrics.lyrics.parser

data class LrcLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null
) {
    fun hasTranslation(): Boolean = !translation.isNullOrBlank()

    fun defaultDisplayText(): String {
        val original = text.trim()
        val translated = translation.orEmpty().trim()
        return when {
            original.isNotBlank() && translated.isNotBlank() -> "$original\n$translated"
            original.isNotBlank() -> original
            translated.isNotBlank() -> translated
            else -> ""
        }
    }
}

object LrcParser {
    private val timeTagRegex = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?]""")
    private const val TRANSLATION_MATCH_TOLERANCE_MS = 500L

    fun parse(lyrics: String): List<LrcLine> {
        return parsePlainLines(lyrics)
    }

    fun parseWithTranslation(lyrics: String, translatedLyrics: String?): List<LrcLine> {
        val originalLines = parsePlainLines(lyrics)
        val translationLines = parsePlainLines(translatedLyrics.orEmpty())

        if (translationLines.isEmpty()) return originalLines
        if (originalLines.isEmpty()) {
            return translationLines.map { line ->
                line.copy(text = "", translation = line.text)
            }
        }

        val usedTranslationIndexes = mutableSetOf<Int>()
        return originalLines.map { original ->
            val translationIndex = findNearestTranslationIndex(
                originalTimeMs = original.timeMs,
                translationLines = translationLines,
                usedIndexes = usedTranslationIndexes
            )
            val translation = translationIndex
                ?.also { usedTranslationIndexes += it }
                ?.let { translationLines[it].text }
                ?.takeIf { it.isNotBlank() && it.trim() != original.text.trim() }

            original.copy(translation = translation ?: original.translation)
        }
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

        return mergedLines.joinToString("\n") { line ->
            val text = when {
                line.text.isNotBlank() && !line.translation.isNullOrBlank() -> {
                    "${line.text.trim()} / ${line.translation.trim()}"
                }
                line.text.isNotBlank() -> line.text.trim()
                !line.translation.isNullOrBlank() -> line.translation.trim()
                else -> ""
            }
            "[${formatTimeTag(line.timeMs)}]$text"
        }
    }

    private fun parsePlainLines(lyrics: String): List<LrcLine> {
        return lyrics
            .lineSequence()
            .flatMap { rawLine ->
                val line = rawLine.trim()
                val matches = timeTagRegex.findAll(line).toList()
                if (matches.isEmpty()) {
                    return@flatMap emptySequence<LrcLine>()
                }

                val text = normalizeDisplayText(line.replace(timeTagRegex, "").trim())
                if (text.isBlank()) {
                    return@flatMap emptySequence<LrcLine>()
                }

                val (originalText, translationText) = splitOriginalAndTranslation(text)

                matches.mapNotNull { match ->
                    val timeMs = parseTimeTag(match) ?: return@mapNotNull null
                    LrcLine(timeMs, originalText, translationText)
                }.asSequence()
            }
            .sortedBy { it.timeMs }
            .toList()
    }

    private fun findNearestTranslationIndex(
        originalTimeMs: Long,
        translationLines: List<LrcLine>,
        usedIndexes: Set<Int>
    ): Int? {
        var bestIndex: Int? = null
        var bestDistance = Long.MAX_VALUE

        translationLines.forEachIndexed { index, line ->
            if (index in usedIndexes) return@forEachIndexed
            val distance = kotlin.math.abs(line.timeMs - originalTimeMs)
            if (distance <= TRANSLATION_MATCH_TOLERANCE_MS && distance < bestDistance) {
                bestIndex = index
                bestDistance = distance
            }
        }

        return bestIndex
    }

    private fun normalizeDisplayText(text: String): String {
        return text
            .replace(" / ", "\n")
            .replace("／", "\n")
            .lines()
            .joinToString("\n") { it.trim() }
            .trim()
    }

    private fun splitOriginalAndTranslation(text: String): Pair<String, String?> {
        val parts = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (parts.size <= 1) return text.trim() to null

        return parts.first() to parts.drop(1).joinToString("\n").takeIf { it.isNotBlank() }
    }

    private fun parseTimeTag(match: MatchResult): Long? {
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toLongOrNull() ?: return null
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

    fun findCurrentLine(lines: List<LrcLine>, positionMs: Long): LrcLine? {
        return findCurrentIndex(lines, positionMs)?.let { lines[it] }
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
