package com.andsi.airlyrics.lyrics.storage

import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import org.json.JSONArray
import org.json.JSONObject

internal object WordByWordLyricsJsonCodec {
    // Persisted compatibility contract. Do not change the serialized values.
    private const val KEY_LINES = "lines"
    private const val KEY_METADATA = "metadata"
    private const val KEY_START_MS = "startMs"
    private const val KEY_END_MS = "endMs"
    private const val KEY_TEXT = "text"
    private const val KEY_SEGMENTS = "tokens"

    data class WordByWordLyricsDocument(
        val wordByWordLines: List<WordByWordLine>,
        val metadataLines: List<String> = emptyList()
    )

    fun wordByWordLinesToJson(
        wordByWordLines: List<WordByWordLine>,
        metadataLines: List<String> = emptyList()
    ): String {
        return wordByWordDocumentToJson(WordByWordLyricsDocument(wordByWordLines, metadataLines))
    }

    private fun wordByWordDocumentToJson(document: WordByWordLyricsDocument): String {
        val linesArray = wordByWordLinesToJsonArray(document.wordByWordLines)
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

    fun parseWordByWordLinesJson(wordByWordJson: String): List<WordByWordLine> {
        return parseWordByWordDocumentJson(wordByWordJson).wordByWordLines
    }

    fun parseWordByWordDocumentJson(wordByWordJson: String): WordByWordLyricsDocument {
        if (wordByWordJson.isBlank()) return WordByWordLyricsDocument(emptyList())

        return runCatching {
            val trimmed = wordByWordJson.trim()
            if (trimmed.startsWith("[")) {
                WordByWordLyricsDocument(wordByWordLines = parseWordByWordLinesArray(JSONArray(trimmed)))
            } else {
                val root = JSONObject(trimmed)
                WordByWordLyricsDocument(
                    wordByWordLines = parseWordByWordLinesArray(root.optJSONArray(KEY_LINES) ?: JSONArray()),
                    metadataLines = parseMetadataArray(root.optJSONArray(KEY_METADATA))
                )
            }
        }.getOrDefault(WordByWordLyricsDocument(emptyList()))
    }

    private fun wordByWordLinesToJsonArray(wordByWordLines: List<WordByWordLine>): JSONArray {
        val array = JSONArray()
        wordByWordLines.sortedBy { it.startMs }.forEach { line ->
            array.put(JSONObject().apply {
                put(KEY_START_MS, line.startMs)
                put(KEY_END_MS, line.endMs)
                put(KEY_TEXT, line.text)
                put(KEY_SEGMENTS, JSONArray().apply {
                    line.segments.forEach { segment ->
                        put(JSONObject().apply {
                            put(KEY_TEXT, segment.text)
                            put(KEY_START_MS, segment.startMs)
                            put(KEY_END_MS, segment.endMs)
                        })
                    }
                })
            })
        }
        return array
    }

    private fun parseWordByWordLinesArray(array: JSONArray): List<WordByWordLine> {
        return buildList {
            for (lineIndex in 0 until array.length()) {
                val line = array.optJSONObject(lineIndex) ?: continue
                val startMs = line.optLong(KEY_START_MS, -1L)
                val endMs = line.optLong(KEY_END_MS, -1L)
                val text = line.optString(KEY_TEXT, "").trim()
                val segmentArray = line.optJSONArray(KEY_SEGMENTS) ?: JSONArray()
                if (startMs !in 0L until endMs || text.isBlank() || segmentArray.length() == 0) continue

                val segments = buildList {
                    for (segmentIndex in 0 until segmentArray.length()) {
                        val segment = segmentArray.optJSONObject(segmentIndex) ?: continue
                        val segmentText = segment.optString(KEY_TEXT, "").trim()
                        val segmentStartMs = segment.optLong(KEY_START_MS, -1L)
                        val segmentEndMs = segment.optLong(KEY_END_MS, -1L)
                        if (segmentText.isNotBlank() && segmentStartMs in startMs until segmentEndMs) {
                            add(WordByWordSegment(segmentText, segmentStartMs, segmentEndMs))
                        }
                    }
                }

                if (segments.isNotEmpty()) add(WordByWordLine(startMs, endMs, text, segments))
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
