package com.andsi.airlyrics.lyrics.storage

import com.andsi.airlyrics.lyrics.KaraokeLine
import com.andsi.airlyrics.lyrics.KaraokeToken
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal object KaraokeLyricsCodec {
    private val timeTagRegex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val wordTimeTagRegex = Regex("""<(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?>""")

    fun linesToJson(lines: List<KaraokeLine>): String {
        val array = JSONArray()
        lines.sortedBy { it.startMs }.forEach { line ->
            array.put(JSONObject().apply {
                put("startMs", line.startMs)
                put("endMs", line.endMs)
                put("text", line.text)
                put("tokens", JSONArray().apply {
                    line.tokens.forEach { token ->
                        put(JSONObject().apply {
                            put("text", token.text)
                            put("startMs", token.startMs)
                            put("endMs", token.endMs)
                        })
                    }
                })
            })
        }
        return array.toString(2)
    }

    fun parseJson(rawJson: String): List<KaraokeLine> {
        if (rawJson.isBlank()) return emptyList()

        return runCatching {
            val array = JSONArray(rawJson)
            buildList {
                for (lineIndex in 0 until array.length()) {
                    val line = array.optJSONObject(lineIndex) ?: continue
                    val startMs = line.optLong("startMs", -1L)
                    val endMs = line.optLong("endMs", -1L)
                    val text = line.optString("text", "").trim()
                    val tokenArray = line.optJSONArray("tokens") ?: JSONArray()
                    if (startMs < 0L || endMs <= startMs || text.isBlank() || tokenArray.length() == 0) continue

                    val tokens = buildList {
                        for (tokenIndex in 0 until tokenArray.length()) {
                            val token = tokenArray.optJSONObject(tokenIndex) ?: continue
                            val tokenText = token.optString("text", "").trim()
                            val tokenStartMs = token.optLong("startMs", -1L)
                            val tokenEndMs = token.optLong("endMs", -1L)
                            if (tokenText.isNotBlank() && tokenStartMs >= startMs && tokenEndMs > tokenStartMs) {
                                add(KaraokeToken(tokenText, tokenStartMs, tokenEndMs))
                            }
                        }
                    }

                    if (tokens.isNotEmpty()) add(KaraokeLine(startMs, endMs, text, tokens))
                }
            }.sortedBy { it.startMs }
        }.getOrDefault(emptyList())
    }

    data class ParsedKaraokeImport(
        val karaokeLines: List<KaraokeLine>,
        val plainLrc: String,
        val hasTranslation: Boolean
    )

    fun parseImport(rawText: String): ParsedKaraokeImport {
        val jsonLines = parseJson(rawText)
        if (jsonLines.isNotEmpty()) {
            return ParsedKaraokeImport(
                karaokeLines = jsonLines,
                plainLrc = linesToPlainLrc(jsonLines),
                hasTranslation = false
            )
        }

        return parseEnhancedLrcImport(rawText)
    }

    fun linesToEnhancedLrc(lines: List<KaraokeLine>): String {
        return lines
            .sortedBy { it.startMs }
            .joinToString("\n") { line ->
                val tokenText = line.tokens
                    .sortedBy { it.startMs }
                    .joinToString(separator = "") { token ->
                        "<${formatLrcTimeTag(token.startMs)}>${token.text}"
                    }
                "[${formatLrcTimeTag(line.startMs)}]$tokenText"
            }
    }

    data class EnhancedLrcValidationResult(
        val isValid: Boolean,
        val invalidLineNumbers: List<Int> = emptyList()
    )

    fun validateEnhancedLrcForStorage(rawLrc: String): EnhancedLrcValidationResult {
        val invalidLineNumbers = rawLrc
            .lineSequence()
            .mapIndexedNotNull { index, rawLine ->
                val line = rawLine.trim()
                if (line.isBlank() || isMetadataLine(line)) {
                    return@mapIndexedNotNull null
                }
                if (isValidEnhancedLrcImportLine(line)) null else index + 1
            }
            .toList()

        if (invalidLineNumbers.isNotEmpty()) {
            return EnhancedLrcValidationResult(isValid = false, invalidLineNumbers = invalidLineNumbers)
        }

        return EnhancedLrcValidationResult(isValid = parseEnhancedLrc(rawLrc).isNotEmpty())
    }

    fun linesToPlainLrc(lines: List<KaraokeLine>): String {
        return linesToPlainLrc(lines, emptyMap())
    }

    fun parseEnhancedLrc(rawLrc: String): List<KaraokeLine> {
        return parseEnhancedLrcImport(rawLrc).karaokeLines
    }

    private data class RawEnhancedLine(
        val startMs: Long,
        val tokens: List<Pair<String, Long>>
    )

    private data class TimedTextSegment(
        val startMs: Long,
        val text: String
    )

    private fun parseEnhancedLrcImport(rawLrc: String): ParsedKaraokeImport {
        if (rawLrc.isBlank()) {
            return ParsedKaraokeImport(emptyList(), plainLrc = "", hasTranslation = false)
        }

        val rawLines = mutableListOf<RawEnhancedLine>()
        val translationCandidates = mutableListOf<TimedTextSegment>()

        rawLrc.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank() || isMetadataLine(line)) return@forEach

            val rawEnhancedLine = parseRawEnhancedLine(line)
            if (rawEnhancedLine != null) {
                rawLines += rawEnhancedLine
                return@forEach
            }

            if (wordTimeTagRegex.containsMatchIn(line)) return@forEach

            translationCandidates += parseTimedTextSegments(line)
        }

        val sortedRawLines = rawLines.sortedBy { it.startMs }
        if (sortedRawLines.isEmpty()) {
            return ParsedKaraokeImport(emptyList(), plainLrc = "", hasTranslation = false)
        }

        val karaokeStartTimes = sortedRawLines.map { it.startMs }.toSet()
        val translationsByStartMs = translationCandidates
            .filter { it.startMs in karaokeStartTimes }
            .groupBy(
                keySelector = { it.startMs },
                valueTransform = { it.text }
            )
            .mapValues { (_, values) -> values.distinct() }

        val karaokeLines = sortedRawLines.mapIndexedNotNull { index, rawLine ->
            val nextLineStart = sortedRawLines.getOrNull(index + 1)?.startMs
            val tokenList = rawLine.tokens.mapIndexed { tokenIndex, (tokenText, tokenStartMs) ->
                val nextTokenStart = rawLine.tokens.getOrNull(tokenIndex + 1)?.second
                    ?: nextLineStart
                    ?: (tokenStartMs + 400L)
                KaraokeToken(
                    text = tokenText,
                    startMs = tokenStartMs,
                    endMs = nextTokenStart.coerceAtLeast(tokenStartMs + 1L)
                )
            }

            val lineText = tokenList.joinToString(separator = "") { it.text }
                .replace(Regex("\\s+"), " ")
                .trim()
            if (lineText.isBlank() || tokenList.isEmpty()) return@mapIndexedNotNull null

            val lineEnd = nextLineStart ?: tokenList.last().endMs
            if (lineEnd <= rawLine.startMs) return@mapIndexedNotNull null

            KaraokeLine(
                startMs = rawLine.startMs,
                endMs = lineEnd,
                text = lineText,
                tokens = tokenList
            )
        }

        if (karaokeLines.isEmpty()) {
            return ParsedKaraokeImport(emptyList(), plainLrc = "", hasTranslation = false)
        }

        val effectiveTranslations = translationsByStartMs
            .mapValues { (startMs, translations) ->
                val originalText = karaokeLines.firstOrNull { it.startMs == startMs }?.text.orEmpty()
                translations
                    .flatMap { splitTranslationParts(it) }
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it != originalText }
                    .distinct()
            }
            .filterValues { it.isNotEmpty() }

        return ParsedKaraokeImport(
            karaokeLines = karaokeLines,
            plainLrc = linesToPlainLrc(karaokeLines, effectiveTranslations),
            hasTranslation = effectiveTranslations.isNotEmpty()
        )
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
        translationsByStartMs: Map<Long, List<String>>
    ): String {
        return lines
            .sortedBy { it.startMs }
            .joinToString("\n") { line ->
                val originalText = line.text.trim()
                val translation = translationsByStartMs[line.startMs]
                    .orEmpty()
                    .flatMap { splitTranslationParts(it) }
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it != originalText }
                    .distinct()
                    .joinToString(" / ")
                val storedText = if (translation.isNotBlank()) {
                    "$originalText / $translation"
                } else {
                    originalText
                }
                "[${formatLrcTimeTag(line.startMs)}]$storedText"
            }
    }

    private fun splitTranslationParts(text: String): List<String> {
        return normalizeTimedText(text)
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun normalizeTimedText(text: String): String {
        return text
            .replace(" / ", "\n")
            .replace("／", "\n")
            .lines()
            .joinToString("\n") { it.trim() }
            .trim()
    }

    private fun isMetadataLine(line: String): Boolean {
        return Regex("""\[[A-Za-z][A-Za-z0-9_\-]*:.*]""").matches(line)
    }

    private fun formatLrcTimeTag(timeMs: Long): String {
        val minutes = timeMs / 60_000L
        val seconds = (timeMs % 60_000L) / 1_000L
        val centiseconds = (timeMs % 1_000L) / 10L
        return "%02d:%02d.%02d".format(Locale.getDefault(), minutes, seconds, centiseconds)
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
}
