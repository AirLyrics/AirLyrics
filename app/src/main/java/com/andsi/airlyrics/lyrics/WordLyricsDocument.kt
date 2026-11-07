package com.andsi.airlyrics.lyrics

import com.andsi.airlyrics.lyrics.parser.WordByWordLrcParser
import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.lyrics.storage.WordByWordLyricsJsonCodec
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** An editor draft keeps word timings and voice/translation data without a lossy LRC round trip. */
internal data class WordLyricsDocument(
    val lines: List<WordByWordLine>,
    val metadata: List<String> = emptyList(),
    val translatedLrc: String? = null
) {
    val originalLrc: String get() = WordByWordLrcParser.wordByWordLinesToPlainLrc(
        lines.filterNot { it.isBackground }, metadata
    )

    fun invalidLines(): List<Int> = lines.mapIndexedNotNull { index, line ->
        val valid = line.startMs >= 0 && line.endMs > line.startMs && line.segments.isNotEmpty() &&
            line.text.isNotBlank() && line.segments.all {
                it.text.isNotBlank() && it.startMs >= 0 && it.endMs > it.startMs &&
                    it.startMs >= line.startMs && it.endMs <= line.endMs + 50
            } && line.segments.zipWithNext().all { (a, b) -> a.startMs <= b.startMs } &&
            line.translatedSegments.all {
                it.text.isNotBlank() && it.startMs >= line.startMs && it.endMs > it.startMs &&
                    it.endMs <= line.endMs + 1
            }
        if (valid) null else index + 1
    }

    fun serialize(): String {
        val encoded = WordByWordLyricsJsonCodec.wordByWordLinesToJson(lines, metadata)
        val root = if (encoded.trimStart().startsWith("[")) JSONObject().put("lines", JSONArray(encoded))
            else JSONObject(encoded)
        root.put("originalLrc", originalLrc)
        translatedLrc?.let { root.put("translatedLrc", it) }
        return root.toString()
    }

    companion object {
        fun parse(text: String): WordLyricsDocument {
            val trimmed = text.trimStart()
            val arrayContent = trimmed.removePrefix("[").trimStart()
            val structured = trimmed.startsWith("{") ||
                trimmed.startsWith("[") && (arrayContent.startsWith("{") || arrayContent.startsWith("]"))
            if (structured) {
                val parsed = WordByWordLyricsJsonCodec.parseWordByWordDocumentJson(text)
                val root = if (trimmed.startsWith("{")) JSONObject(text) else null
                val rawLines = root?.getJSONArray("lines") ?: JSONArray(text)
                // The playback codec is tolerant. Editing must never silently drop a row or token.
                require(rawLines.length() == parsed.wordByWordLines.size) { "Invalid word lyric rows" }
                val ordered = (0 until rawLines.length()).map { rawLines.getJSONObject(it) }.sortedBy { it.getLong("startMs") }
                ordered.zip(parsed.wordByWordLines).forEach { (raw, line) ->
                    require(raw.getJSONArray("tokens").length() == line.segments.size &&
                        (raw.optJSONArray("translatedTokens")?.length() ?: 0) == line.translatedSegments.size) {
                        "Invalid word lyric tokens"
                    }
                }
                // Preserve editor text verbatim; the playback codec trims display-only edge whitespace.
                val exactLines = ordered.zip(parsed.wordByWordLines).map { (raw, line) ->
                    line.copy(text = raw.getString("text"))
                }
                return WordLyricsDocument(exactLines, parsed.metadataLines,
                    root?.optString("translatedLrc")?.takeIf { it.isNotEmpty() })
            }
            // Drafts written by the previous text editor are still restorable.
            val legacy = WordByWordLrcParser.parseImport(text)
            val translations = LrcParser.parse(legacy.plainLrc).filter { it.hasTranslation() }
                .joinToString("\n") { "[${time(it.timeMs)}]${it.translation}" }.takeIf { it.isNotBlank() }
            return WordLyricsDocument(legacy.wordByWordLines, legacy.metadataLines, translations)
        }

        fun time(ms: Long): String = String.format(Locale.ROOT, "%02d:%02d.%03d", ms / 60000, ms / 1000 % 60, ms % 1000)

        fun parseTime(value: String): Long? {
            val match = Regex("""^(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?$""").matchEntire(value.trim())
                ?: return null
            val seconds = match.groupValues[2].toLong()
            if (seconds >= 60) return null
            return match.groupValues[1].toLong() * 60000 + seconds * 1000 +
                match.groupValues[3].padEnd(3, '0').toLong()
        }
    }
}
