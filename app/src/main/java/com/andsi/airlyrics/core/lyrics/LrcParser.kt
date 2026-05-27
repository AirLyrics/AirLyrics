package com.andsi.airlyrics

data class LrcLine(
    val timeMs: Long,
    val text: String
)

object LrcParser {
    private val timeTagRegex = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?]""")

    fun parse(lyrics: String): List<LrcLine> {
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

                matches.mapNotNull { match ->
                    val timeMs = parseTimeTag(match) ?: return@mapNotNull null
                    LrcLine(timeMs, text)
                }.asSequence()
            }
            .sortedBy { it.timeMs }
            .toList()
    }

    private fun normalizeDisplayText(text: String): String {
        return text
            .replace(" / ", "\n")
            .replace("／", "\n")
            .lines()
            .joinToString("\n") { it.trim() }
            .trim()
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

    fun findCurrentLine(lines: List<LrcLine>, positionMs: Long): LrcLine? {
        if (lines.isEmpty()) return null

        var left = 0
        var right = lines.lastIndex
        var result: LrcLine? = null

        while (left <= right) {
            val mid = (left + right) / 2
            val line = lines[mid]

            if (line.timeMs <= positionMs) {
                result = line
                left = mid + 1
            } else {
                right = mid - 1
            }
        }

        return result
    }
}
