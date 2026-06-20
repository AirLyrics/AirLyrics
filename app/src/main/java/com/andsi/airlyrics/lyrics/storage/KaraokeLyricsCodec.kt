package com.andsi.airlyrics.lyrics.storage

import com.andsi.airlyrics.lyrics.KaraokeLine
import com.andsi.airlyrics.lyrics.KaraokeToken
import org.json.JSONArray
import org.json.JSONObject

internal object KaraokeLyricsCodec {
    private const val KEY_LINES = "lines"
    private const val KEY_METADATA = "metadata"

    data class KaraokeLyricsDocument(
        val lines: List<KaraokeLine>,
        val metadataLines: List<String> = emptyList()
    )

    fun linesToJson(
        lines: List<KaraokeLine>,
        metadataLines: List<String> = emptyList()
    ): String {
        return documentToJson(KaraokeLyricsDocument(lines, metadataLines))
    }

    private fun documentToJson(document: KaraokeLyricsDocument): String {
        val linesArray = linesToJsonArray(document.lines)
        if (document.metadataLines.isEmpty()) {
            return linesArray.toString(2)
        }

        return JSONObject().apply {
            put(KEY_METADATA, JSONArray().apply {
                document.metadataLines.forEach { metadataLine ->
                    put(metadataLine)
                }
            })
            put(KEY_LINES, linesArray)
        }.toString(2)
    }

    fun parseJson(rawJson: String): List<KaraokeLine> {
        return parseDocumentJson(rawJson).lines
    }

    fun parseDocumentJson(rawJson: String): KaraokeLyricsDocument {
        if (rawJson.isBlank()) return KaraokeLyricsDocument(emptyList())

        return runCatching {
            val trimmed = rawJson.trim()
            if (trimmed.startsWith("[")) {
                KaraokeLyricsDocument(lines = parseLinesArray(JSONArray(trimmed)))
            } else {
                val root = JSONObject(trimmed)
                KaraokeLyricsDocument(
                    lines = parseLinesArray(root.optJSONArray(KEY_LINES) ?: JSONArray()),
                    metadataLines = parseMetadataArray(root.optJSONArray(KEY_METADATA))
                )
            }
        }.getOrDefault(KaraokeLyricsDocument(emptyList()))
    }

    private fun linesToJsonArray(lines: List<KaraokeLine>): JSONArray {
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
        return array
    }

    private fun parseLinesArray(array: JSONArray): List<KaraokeLine> {
        return buildList {
            for (lineIndex in 0 until array.length()) {
                val line = array.optJSONObject(lineIndex) ?: continue
                val startMs = line.optLong("startMs", -1L)
                val endMs = line.optLong("endMs", -1L)
                val text = line.optString("text", "").trim()
                val tokenArray = line.optJSONArray("tokens") ?: JSONArray()
                if (startMs !in 0L until endMs || text.isBlank() || tokenArray.length() == 0) continue

                val tokens = buildList {
                    for (tokenIndex in 0 until tokenArray.length()) {
                        val token = tokenArray.optJSONObject(tokenIndex) ?: continue
                        val tokenText = token.optString("text", "").trim()
                        val tokenStartMs = token.optLong("startMs", -1L)
                        val tokenEndMs = token.optLong("endMs", -1L)
                        if (tokenText.isNotBlank() && tokenStartMs in startMs until tokenEndMs) {
                            add(KaraokeToken(tokenText, tokenStartMs, tokenEndMs))
                        }
                    }
                }

                if (tokens.isNotEmpty()) add(KaraokeLine(startMs, endMs, text, tokens))
            }
        }.sortedBy { it.startMs }
    }

    private fun parseMetadataArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()

        return buildList {
            for (index in 0 until array.length()) {
                val metadataLine = array.optString(index, "").trim()
                if (metadataLine.isNotBlank()) add(metadataLine)
            }
        }
    }
}
