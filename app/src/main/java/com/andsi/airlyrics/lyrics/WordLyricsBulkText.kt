package com.andsi.airlyrics.lyrics

import com.andsi.airlyrics.lyrics.parser.WordByWordLrcParser

internal object WordLyricsBulkText {
    data class Result(val document: WordLyricsDocument?, val invalidLines: List<Int> = emptyList())

    private const val TIME_PATTERN = "\\d{2,}:\\d{2}\\.\\d{3}"
    private val rowTag = Regex("^\\[(bg )?($TIME_PATTERN)-($TIME_PATTERN)]")
    private val segment = Regex("<($TIME_PATTERN)-($TIME_PATTERN)>((?:\\\\[\\\\<>nr]|[^\\\\<>\\r\\n])*+)")
    private val metadataTag = Regex("^\\[[A-Za-z][A-Za-z0-9_-]*:.*]$")
    private val nativeTags = listOf("[text]", "[tr]", "[tr-words]", "[tr-lrc]", "[meta]", "[bg ")

    fun format(document: WordLyricsDocument): String = buildList {
        document.metadata.forEach { tag ->
            val canUseTag = metadataTag.matches(tag) && ('\\' !in tag) && ('\r' !in tag) && ('\n' !in tag)
            add(if (canUseTag) tag else "[meta]" + escape(tag))
        }
        document.lines.forEach { line ->
            val background = if (line.isBackground) "bg " else ""
            add("[$background${range(line.startMs, line.endMs)}]${formatSegments(line.segments)}")
            if (line.text != line.segments.joinToString("") { it.text }) add("[text]" + escape(line.text))
            if (line.translatedText.isNotEmpty()) add("[tr]" + escape(line.translatedText))
            if (line.translatedSegments.isNotEmpty()) add("[tr-words]" + formatSegments(line.translatedSegments))
        }
        document.translatedLrc?.let { add("[tr-lrc]" + escape(it)) }
    }.joinToString("\n")

    fun parse(text: String): Result {
        val source = text.lines()
        val native = source.any { line -> rowTag.containsMatchIn(line) || nativeTags.any(line::startsWith) }
        if (!native && source.any { it.isNotBlank() && !metadataTag.matches(it.trim()) }) {
            val validation = WordByWordLrcParser.validateForStorage(text)
            if (!validation.isValid) return Result(null, validation.invalidLineNumbers)
            val document = WordLyricsDocument.parse(text)
            return if (document.lines.isNotEmpty() && document.invalidLines().isEmpty()) Result(document)
                else Result(null, document.invalidLines())
        }

        val rows = mutableListOf<WordByWordLine>()
        val rowNumbers = mutableListOf<Int>()
        val metadata = mutableListOf<String>()
        val invalid = mutableSetOf<Int>()
        val attached = mutableSetOf<String>()
        var activeRow = -1
        var translatedLrc: String? = null

        source.forEachIndexed { index, line ->
            val number = index + 1
            val tag = rowTag.find(line)
            when {
                line.isBlank() -> Unit
                tag != null -> {
                    activeRow = -1
                    attached.clear()
                    val start = parseTime(tag.groupValues[2])
                    val end = parseTime(tag.groupValues[3])
                    val words = parseSegments(line.substring(tag.range.last + 1))
                    if ((start == null) || (end == null) || (words == null)) {
                        invalid += number
                    } else {
                        rows += WordByWordLine(
                            startMs = start,
                            endMs = end,
                            text = words.joinToString("") { it.text },
                            segments = words,
                            isBackground = tag.groupValues[1].isNotEmpty(),
                        )
                        rowNumbers += number
                        activeRow = rows.lastIndex
                    }
                }
                line.startsWith("[meta]") -> {
                    val value = unescape(line.substring(6))
                    if (value.isNullOrBlank()) invalid += number else metadata += value
                    activeRow = -1
                }
                metadataTag.matches(line) -> {
                    metadata += line.trim()
                    activeRow = -1
                }
                line.startsWith("[tr-lrc]") -> {
                    val value = unescape(line.substring(8))
                    if ((value == null) || (translatedLrc != null)) invalid += number else translatedLrc = value
                    activeRow = -1
                }
                else -> {
                    val marker = line.substringBefore(']') + "]"
                    val row = rows.getOrNull(activeRow)
                    if ((row == null) || !attached.add(marker)) {
                        invalid += number
                        return@forEachIndexed
                    }
                    val value = line.drop(marker.length)
                    val updated = when (marker) {
                        "[text]" -> unescape(value)?.takeIf(String::isNotBlank)?.let { row.copy(text = it) }
                        "[tr]" -> unescape(value)?.let { row.copy(translatedText = it) }
                        "[tr-words]" -> parseSegments(value)?.takeIf { words ->
                            val latestEnd = row.endMs + 1
                            words.all { (it.startMs >= row.startMs) && (it.endMs <= latestEnd) }
                        }?.let { row.copy(translatedSegments = it) }
                        else -> null
                    }
                    if (updated == null) invalid += number else rows[activeRow] = updated
                }
            }
        }

        val document = WordLyricsDocument(rows, metadata, translatedLrc)
        document.invalidLines().forEach { invalid += rowNumbers[it - 1] }
        return if (invalid.isEmpty()) Result(document.copy(lines = rows.sortedBy { it.startMs })) else Result(null, invalid.sorted())
    }

    private fun formatSegments(segments: List<WordByWordSegment>): String =
        segments.joinToString("") { "<${range(it.startMs, it.endMs)}>${escape(it.text)}" }

    private fun range(start: Long, end: Long): String =
        "${WordLyricsDocument.time(start)}-${WordLyricsDocument.time(end)}"

    private fun parseTime(value: String): Long? {
        val minutes = value.substringBefore(':').toLongOrNull() ?: return null
        val seconds = value.substringAfter(':').substringBefore('.').toLong()
        val millis = value.substringAfter('.').toLong()
        val maxMinutes = (Long.MAX_VALUE - 60_050) / 60_000
        if ((seconds >= 60) || (minutes > maxMinutes)) return null
        return (minutes * 60_000) + (seconds * 1_000) + millis
    }

    private fun parseSegments(value: String): List<WordByWordSegment>? {
        val words = mutableListOf<WordByWordSegment>()
        var offset = 0
        for (match in segment.findAll(value)) {
            if (match.range.first != offset) return null
            val start = parseTime(match.groupValues[1]) ?: return null
            val end = parseTime(match.groupValues[2]) ?: return null
            val text = unescape(match.groupValues[3]) ?: return null
            if (text.isBlank() || (start >= end)) return null
            words += WordByWordSegment(text, start, end)
            offset = match.range.last + 1
        }
        return words.takeIf { it.isNotEmpty() && (offset == value.length) }
    }

    private fun escape(text: String): String = buildString {
        text.forEach { character ->
            when (character) {
                '\\', '<', '>' -> append('\\').append(character)
                '\r' -> append("\\r")
                '\n' -> append("\\n")
                else -> append(character)
            }
        }
    }

    private fun unescape(text: String): String? = buildString {
        var index = 0
        while (index < text.length) {
            val character = text[index++]
            if (character != '\\') {
                append(character)
                continue
            }
            if (index == text.length) return null
            when (val escaped = text[index++]) {
                '\\', '<', '>' -> append(escaped)
                'r' -> append('\r')
                'n' -> append('\n')
                else -> return null
            }
        }
    }
}
