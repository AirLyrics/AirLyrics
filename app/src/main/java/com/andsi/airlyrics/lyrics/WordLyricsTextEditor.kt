package com.andsi.airlyrics.lyrics

/** The raw editor exposes exact token boundaries and timings, without estimating new word times. */
internal object WordLyricsTextEditor {
    fun rawLine(line: WordByWordLine): String = line.segments.mapIndexed { index, segment ->
        val end = if (line.segments.getOrNull(index + 1)?.startMs == segment.endMs) ""
            else "<${WordLyricsDocument.time(segment.endMs)}>"
        "<${WordLyricsDocument.time(segment.startMs)}>${segment.text}$end"
    }.joinToString("")

    /** Accept a single enhanced LRC line, with an optional ordinary line-start tag when pasted. */
    fun parseRawLine(source: WordByWordLine, raw: String): WordByWordLine? {
        if ('\n' in raw || '\r' in raw) return null
        var content = raw.trim()
        var lineStart: Long? = null
        if (content.startsWith("[")) {
            val end = content.indexOf(']')
            if (end < 0) return null
            lineStart = WordLyricsDocument.parseTime(content.substring(1, end)) ?: return null
            content = content.substring(end + 1)
        }
        val tags = timeTags.findAll(content).toList()
        if (tags.size < 2 || tags.first().range.first != 0 || tags.last().range.last != content.lastIndex) return null
        val times = tags.map { WordLyricsDocument.parseTime(it.groupValues[1]) ?: return null }
        val segments = mutableListOf<WordByWordSegment>()
        var followsWord = false
        tags.zipWithNext().forEachIndexed { index, (tag, next) ->
            val text = content.substring(tag.range.last + 1, next.range.first)
            // Consecutive tags preserve silence or overlapping word endings; they are not empty words.
            if (text.isEmpty()) {
                if (!followsWord || index == tags.lastIndex - 1) return null
                followsWord = false
                return@forEachIndexed
            }
            followsWord = true
            if (text.isBlank() || '<' in text || '>' in text || times[index + 1] <= times[index]) return null
            segments += WordByWordSegment(text, times[index], times[index + 1])
        }
        if (segments.isEmpty()) return null
        val updated = withSegments(source, segments, lineStart)
        return updated.takeIf { WordLyricsDocument(listOf(it)).invalidLines().isEmpty() }
    }

    /** Bounds follow explicit word times. Unchanged leading/trailing padding stays lossless. */
    fun withSegments(
        source: WordByWordLine,
        segments: List<WordByWordSegment>,
        lineStart: Long? = null
    ): WordByWordLine {
        if (segments.isEmpty()) return source.copy(text = "", segments = emptyList())
        val firstStart = segments.first().startMs
        val leading = source.segments.firstOrNull()?.startMs?.minus(source.startMs) ?: 0L
        val start = lineStart ?: (firstStart - leading).coerceIn(0, firstStart)
        val lastEnd = segments.maxOf { it.endMs }
        val trailing = source.endMs - (source.segments.maxOfOrNull { it.endMs } ?: source.endMs)
        val end = maxOf(lastEnd, lastEnd + trailing)
        val shift = start - source.startMs
        val translated = source.translatedSegments.map {
            it.copy(startMs = it.startMs + shift, endMs = it.endMs + shift)
        }
        return source.copy(
            startMs = start,
            endMs = maxOf(end, translated.maxOfOrNull { it.endMs } ?: end),
            text = if (segments == source.segments) source.text else segments.joinToString("") { it.text },
            segments = segments,
            translatedSegments = translated
        )
    }

    private val timeTags = Regex("<([^<>]*)>")
}
