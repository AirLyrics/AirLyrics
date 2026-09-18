package com.andsi.airlyrics.lyrics.providers

import java.util.EnumMap
import java.util.Locale

internal data class LrclibEmbeddedLyricsTracks(
    val originalLrc: String,
    val translatedLrc: String?
)

/**
 * Some community-provided LRCLIB entries encode a secondary lyric track as a second line with
 * the same timestamp. LRCLIB does not label that line as a translation, so only split tracks when
 * the whole file provides strong structural and writing-system evidence.
 */
internal fun splitLrclibEmbeddedTranslations(lrc: String): LrclibEmbeddedLyricsTracks {
    val unchanged = LrclibEmbeddedLyricsTracks(originalLrc = lrc, translatedLrc = null)
    if (lrc.isBlank()) return unchanged

    val rawLines = lrc.split('\n')
    val timedLines = mutableListOf<LrclibTimedLine>()

    rawLines.forEachIndexed { index, rawLine ->
        val timestampCount = lrclibTimestampRegex.findAll(rawLine).count()
        if (timestampCount == 0) return@forEachIndexed
        if (timestampCount != 1) return unchanged

        timedLines += parseLrclibTimedLine(index, rawLine) ?: return unchanged
    }

    val lyricGroups = timedLines
        .groupBy { it.timeMs }
        .values
        .filter { lines -> lines.any { it.isEligibleLyric } }
    if (lyricGroups.isEmpty()) return unchanged

    val candidates = lyricGroups.mapNotNull(::toTranslationCandidate)
    if (candidates.size < MIN_LRCLIB_TRANSLATION_PAIRS) return unchanged

    val originalSystem = dominantWritingSystem(candidates.map { it.original.text })
        ?: return unchanged
    val translatedSystem = dominantWritingSystem(candidates.map { it.translation.text })
        ?: return unchanged
    if (originalSystem == translatedSystem) return unchanged

    // Aggregate script differences are not enough when the two positions switch languages.
    // Require repeated per-row evidence in one direction and reject any clear reversal.
    val supportedCandidates = mutableListOf<LrclibTranslationCandidate>()
    candidates.forEach { candidate ->
        val firstSystem = dominantWritingSystem(
            texts = listOf(candidate.original.text),
            minimumLetters = MIN_LOCAL_SCRIPT_LETTERS
        )
        val secondSystem = dominantWritingSystem(
            texts = listOf(candidate.translation.text),
            minimumLetters = MIN_LOCAL_SCRIPT_LETTERS
        )

        if (firstSystem == translatedSystem && secondSystem == originalSystem) {
            return unchanged
        }
        if (firstSystem == originalSystem && secondSystem == translatedSystem) {
            supportedCandidates += candidate
        }
    }
    if (supportedCandidates.size < MIN_LRCLIB_TRANSLATION_PAIRS) return unchanged

    // Isolated duplicate timestamps are common for layered vocals. Only locally supported pairs
    // count toward coverage; ambiguous pairs remain untouched in the original track.
    if (supportedCandidates.size.toLong() * 3L < lyricGroups.size.toLong() * 2L) return unchanged

    val translatedLineIndexes = supportedCandidates
        .asSequence()
        .map { it.translation.index }
        .toSet()
    val originalLrc = rawLines
        .filterIndexed { index, _ -> index !in translatedLineIndexes }
        .joinToString("\n")
    val translatedLrc = supportedCandidates
        .sortedBy { it.translation.index }
        .joinToString("\n") { rawLines[it.translation.index] }
        .takeIf { it.isNotBlank() }
        ?: return unchanged

    return LrclibEmbeddedLyricsTracks(
        originalLrc = originalLrc,
        translatedLrc = translatedLrc
    )
}

private data class LrclibTimedLine(
    val index: Int,
    val timeMs: Long,
    val text: String,
    val isEligibleLyric: Boolean
)

private data class LrclibTranslationCandidate(
    val original: LrclibTimedLine,
    val translation: LrclibTimedLine
)

private enum class LrclibWritingSystem {
    JAPANESE,
    HAN,
    HANGUL,
    LATIN,
    CYRILLIC,
    ARABIC,
    DEVANAGARI,
    GREEK,
    HEBREW,
    THAI
}

private fun parseLrclibTimedLine(index: Int, rawLine: String): LrclibTimedLine? {
    val match = lrclibSingleTimedLineRegex.matchEntire(rawLine) ?: return null
    val minutes = match.groupValues[1].toLongOrNull() ?: return null
    val seconds = match.groupValues[2].toLongOrNull()?.takeIf { it < 60L } ?: return null

    val fraction = match.groupValues[3]
    val millis = when (fraction.length) {
        0 -> 0L
        1 -> fraction.toLong() * 100L
        2 -> fraction.toLong() * 10L
        else -> fraction.take(3).toLong()
    }
    val subMinuteMs = seconds * 1_000L + millis
    if (minutes > (Long.MAX_VALUE - subMinuteMs) / 60_000L) return null
    val text = match.groupValues[4].trim()

    return LrclibTimedLine(
        index = index,
        timeMs = minutes * 60_000L + subMinuteMs,
        text = text,
        isEligibleLyric = text.isNotBlank() &&
            !lrclibMetadataTextRegex.matches(text) &&
            !lrclibKeyValueTextRegex.containsMatchIn(text)
    )
}

private fun toTranslationCandidate(
    timestampLines: List<LrclibTimedLine>
): LrclibTranslationCandidate? {
    if (timestampLines.size != 2 || timestampLines.any { !it.isEligibleLyric }) return null

    val (first, second) = timestampLines.sortedBy { it.index }
    if (second.index != first.index + 1) return null
    if (first.text.normalizedLrclibText() == second.text.normalizedLrclibText()) return null

    return LrclibTranslationCandidate(original = first, translation = second)
}

private fun dominantWritingSystem(
    texts: List<String>,
    minimumLetters: Int = MIN_AGGREGATE_SCRIPT_LETTERS
): LrclibWritingSystem? {
    val counts = EnumMap<LrclibWritingSystem, Int>(LrclibWritingSystem::class.java)
    var letterCount = 0
    var kanaCount = 0
    var hanCount = 0

    texts.forEach { text ->
        var offset = 0
        while (offset < text.length) {
            val codePoint = Character.codePointAt(text, offset)
            offset += Character.charCount(codePoint)
            if (!Character.isLetter(codePoint)) continue

            letterCount++
            when (Character.UnicodeScript.of(codePoint)) {
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA -> kanaCount++
                Character.UnicodeScript.HAN -> hanCount++
                Character.UnicodeScript.HANGUL -> counts.increment(LrclibWritingSystem.HANGUL)
                Character.UnicodeScript.LATIN -> counts.increment(LrclibWritingSystem.LATIN)
                Character.UnicodeScript.CYRILLIC -> counts.increment(LrclibWritingSystem.CYRILLIC)
                Character.UnicodeScript.ARABIC -> counts.increment(LrclibWritingSystem.ARABIC)
                Character.UnicodeScript.DEVANAGARI -> counts.increment(LrclibWritingSystem.DEVANAGARI)
                Character.UnicodeScript.GREEK -> counts.increment(LrclibWritingSystem.GREEK)
                Character.UnicodeScript.HEBREW -> counts.increment(LrclibWritingSystem.HEBREW)
                Character.UnicodeScript.THAI -> counts.increment(LrclibWritingSystem.THAI)
                else -> Unit
            }
        }
    }

    if (letterCount < minimumLetters) return null

    if (kanaCount > 0) {
        val japaneseLetterCount = kanaCount + hanCount
        return if (japaneseLetterCount * 5 >= letterCount * 3) {
            LrclibWritingSystem.JAPANESE
        } else {
            null
        }
    }
    if (hanCount > 0) counts[LrclibWritingSystem.HAN] = hanCount

    val dominant = counts.maxByOrNull { it.value } ?: return null
    return dominant.key.takeIf { dominant.value * 5 >= letterCount * 3 }
}

private fun EnumMap<LrclibWritingSystem, Int>.increment(system: LrclibWritingSystem) {
    this[system] = (this[system] ?: 0) + 1
}

private fun String.normalizedLrclibText(): String {
    return trim()
        .replace(lrclibWhitespaceRegex, " ")
        .lowercase(Locale.ROOT)
}

private const val MIN_LRCLIB_TRANSLATION_PAIRS = 3
private const val MIN_AGGREGATE_SCRIPT_LETTERS = 6
private const val MIN_LOCAL_SCRIPT_LETTERS = 2

private val lrclibTimestampRegex =
    Regex("""\[(\d+):(\d{2})(?:[.:](\d{1,3}))?]""")
private val lrclibSingleTimedLineRegex =
    Regex("""^\s*\[(\d+):(\d{2})(?:[.:](\d{1,3}))?](.*)$""")
private val lrclibMetadataTextRegex = Regex("""\[[A-Za-z][A-Za-z0-9_\-]*:.*]""")
private val lrclibKeyValueTextRegex = Regex("""^[^\n]{1,32}\s*[:：]""")
private val lrclibWhitespaceRegex = Regex("""\s+""")
