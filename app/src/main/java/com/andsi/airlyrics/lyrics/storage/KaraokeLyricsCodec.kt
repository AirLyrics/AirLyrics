package com.andsi.airlyrics.lyrics.storage

import com.andsi.airlyrics.lyrics.KaraokeLine
import com.andsi.airlyrics.lyrics.KaraokeToken
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal object KaraokeLyricsCodec {
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

    fun linesToPlainLrc(lines: List<KaraokeLine>): String {
        return lines
            .sortedBy { it.startMs }
            .joinToString("\n") { line ->
                "[${formatLrcTimeTag(line.startMs)}]${line.text.trim()}"
            }
    }

    fun parseEnhancedLrc(rawLrc: String): List<KaraokeLine> {
        if (rawLrc.isBlank()) return emptyList()

        data class RawEnhancedLine(
            val startMs: Long,
            val content: String,
            val tokens: List<Pair<String, Long>>
        )

        val timeTagRegex = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
        val wordTimeTagRegex = Regex("""<(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?>""")

        val rawLines = rawLrc
            .lineSequence()
            .mapNotNull { rawLine ->
                val line = rawLine.trim()
                if (line.isBlank()) return@mapNotNull null

                val lineStart = timeTagRegex.find(line)?.let { parseTimeTag(it) }
                    ?: return@mapNotNull null
                val content = line.replace(timeTagRegex, "").trim()
                val wordTags = wordTimeTagRegex.findAll(content).toList()
                if (wordTags.isEmpty()) return@mapNotNull null

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

                if (tokens.isEmpty()) null else RawEnhancedLine(lineStart, content, tokens)
            }
            .sortedBy { it.startMs }
            .toList()

        if (rawLines.isEmpty()) return emptyList()

        return rawLines.mapIndexedNotNull { index, rawLine ->
            val nextLineStart = rawLines.getOrNull(index + 1)?.startMs
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
