package com.andsi.airlyrics.lyrics.storage

import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import com.andsi.airlyrics.lyrics.parser.WordByWordLrcParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class WordByWordLyricsJsonCodecTest {
    @Test
    fun wordByWordJsonParser_readsFixedPersistedArrayFixture() {
        val document = WordByWordLyricsJsonCodec.parseWordByWordDocumentJson(LEGACY_WORD_BY_WORD_JSON_FIXTURE)

        assertEquals(emptyList<String>(), document.metadataLines)
        assertEquals(
            listOf(
                WordByWordLine(
                    startMs = 5_000L,
                    endMs = 6_000L,
                    text = "hi",
                    segments = listOf(
                        WordByWordSegment("h", 5_000L, 5_300L),
                        WordByWordSegment("i", 5_300L, 6_000L)
                    )
                )
            ),
            document.wordByWordLines
        )
    }

    @Test
    fun wordByWordJsonSerializer_writesRequiredPersistentSchemasAndValues() {
        val wordByWordLines = listOf(
            WordByWordLine(
                startMs = 5_000L,
                endMs = 6_000L,
                text = "hi",
                segments = listOf(
                    WordByWordSegment("h", 5_000L, 5_300L),
                    WordByWordSegment("i", 5_300L, 6_000L)
                )
            )
        )
        val metadataLines = listOf("[ar:Artist]", "[ti:Title]")

        val legacyRoot = JSONArray(WordByWordLyricsJsonCodec.wordByWordLinesToJson(wordByWordLines))
        assertEquals(1, legacyRoot.length())
        assertSerializedLine(legacyRoot.getJSONObject(0))

        val documentRoot =
            JSONObject(WordByWordLyricsJsonCodec.wordByWordLinesToJson(wordByWordLines, metadataLines))
        val metadata = documentRoot.getJSONArray("metadata")
        assertEquals(2, metadata.length())
        assertEquals("[ar:Artist]", metadata.getString(0))
        assertEquals("[ti:Title]", metadata.getString(1))
        val serializedLines = documentRoot.getJSONArray("lines")
        assertEquals(1, serializedLines.length())
        assertSerializedLine(serializedLines.getJSONObject(0))
    }

    @Test
    fun wordByWordJsonRoundTrip_preservesSpecialTextSemantics() {
        val specialText = "He said \"歌\" at C:\\Lyrics\n日本語與繁體中文"
        val originalWordByWordLines = listOf(
            WordByWordLine(
                startMs = 7_000L,
                endMs = 8_000L,
                text = specialText,
                segments = listOf(
                    WordByWordSegment(
                        text = specialText,
                        startMs = 7_000L,
                        endMs = 8_000L
                    )
                )
            )
        )

        val parsedWordByWordLines = WordByWordLyricsJsonCodec.parseWordByWordLinesJson(
            WordByWordLyricsJsonCodec.wordByWordLinesToJson(originalWordByWordLines)
        )

        assertEquals(originalWordByWordLines, parsedWordByWordLines)
    }

    @Test
    fun wordByWordJsonRoundTrip_preservesSpacesBetweenSegments() {
        val originalWordByWordLines = listOf(
            WordByWordLine(
                startMs = 10_000L,
                endMs = 11_000L,
                text = "I love you",
                segments = listOf(
                    WordByWordSegment("I ", 10_000L, 10_300L),
                    WordByWordSegment("love ", 10_300L, 10_600L),
                    WordByWordSegment("you", 10_600L, 11_000L)
                )
            )
        )

        val parsedWordByWordLines = WordByWordLyricsJsonCodec.parseWordByWordLinesJson(
            WordByWordLyricsJsonCodec.wordByWordLinesToJson(originalWordByWordLines)
        )

        assertEquals(originalWordByWordLines, parsedWordByWordLines)
        assertEquals(
            "[00:10.00]<00:10.00>I <00:10.30>love <00:10.60>you",
            WordByWordLrcParser.wordByWordLinesToWordByWordLrc(parsedWordByWordLines)
        )
    }

    @Test
    fun additionalDocumentAndLineFieldsRemainCompatibleWithHistoricalSchema() {
        val lines = JSONArray(LEGACY_WORD_BY_WORD_JSON_FIXTURE)
        lines.getJSONObject(0).put("futureLineMetadata", "ignored")
        val json = JSONObject().put("metadata", JSONArray().put("[ar:Artist]"))
            .put("lines", lines).put("futureDocumentMetadata", true)
        val parsed = WordByWordLyricsJsonCodec.parseWordByWordDocumentJson(json.toString())
        assertEquals(listOf("[ar:Artist]"), parsed.metadataLines)
        assertEquals("hi", parsed.wordByWordLines.single().text)
        assertEquals(5_000L, parsed.wordByWordLines.single().startMs)
    }

    private fun assertSerializedLine(wordByWordLineJson: JSONObject) {
        assertEquals(5_000L, wordByWordLineJson.getLong("startMs"))
        assertEquals(6_000L, wordByWordLineJson.getLong("endMs"))
        assertEquals("hi", wordByWordLineJson.getString("text"))

        val wordByWordSegmentsJson = wordByWordLineJson.getJSONArray("tokens")
        assertEquals(2, wordByWordSegmentsJson.length())
        assertSerializedSegment(wordByWordSegmentsJson.getJSONObject(0), "h", 5_000L, 5_300L)
        assertSerializedSegment(wordByWordSegmentsJson.getJSONObject(1), "i", 5_300L, 6_000L)
    }

    private fun assertSerializedSegment(
        wordByWordSegmentJson: JSONObject,
        expectedText: String,
        expectedStartMs: Long,
        expectedEndMs: Long
    ) {
        assertEquals(expectedText, wordByWordSegmentJson.getString("text"))
        assertEquals(expectedStartMs, wordByWordSegmentJson.getLong("startMs"))
        assertEquals(expectedEndMs, wordByWordSegmentJson.getLong("endMs"))
    }

    private companion object {
        val LEGACY_WORD_BY_WORD_JSON_FIXTURE = """
            [
              {
                "startMs": 5000,
                "endMs": 6000,
                "text": "hi",
                "tokens": [
                  {"text": "h", "startMs": 5000, "endMs": 5300},
                  {"text": "i", "startMs": 5300, "endMs": 6000}
                ]
              }
            ]
        """.trimIndent()
    }
}
