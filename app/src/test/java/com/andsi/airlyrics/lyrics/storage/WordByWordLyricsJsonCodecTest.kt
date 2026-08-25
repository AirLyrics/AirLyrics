package com.andsi.airlyrics.lyrics.storage

import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import com.andsi.airlyrics.lyrics.parser.WordByWordLrcParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordByWordLyricsJsonCodecTest {
    @Test
    fun parseWordByWordLrc_buildsSegmentTimingLines() {
        val wordByWordLines = WordByWordLrcParser.parse(
            """
            [00:10.00]<00:10.00>he<00:10.30>llo <00:10.60>world
            [00:12.00]<00:12.00>next
            """.trimIndent()
        )

        assertEquals(2, wordByWordLines.size)
        assertEquals(10_000L, wordByWordLines[0].startMs)
        assertEquals(12_000L, wordByWordLines[0].endMs)
        assertEquals("hello world", wordByWordLines[0].text)
        assertEquals(listOf("he", "llo ", "world"), wordByWordLines[0].segments.map { it.text })
        assertEquals(listOf(10_000L, 10_300L, 10_600L), wordByWordLines[0].segments.map { it.startMs })
        assertEquals(12_000L, wordByWordLines[0].segments.last().endMs)
    }

    @Test
    fun parseWordByWordLrc_ignoresInvalidOrUntimedLines() {
        val wordByWordLines = WordByWordLrcParser.parse(
            """
            plain text
            [00:01.00]no word tags
            [00:02.00]<00:01.50>starts before line
            [00:03.00]<00:03.00>valid
            """.trimIndent()
        )

        assertEquals(1, wordByWordLines.size)
        assertEquals("valid", wordByWordLines.single().text)
    }

    @Test
    fun wordByWordLinesToPlainLrc_preservesLineTextAndTiming() {
        val plainLrc = WordByWordLrcParser.wordByWordLinesToPlainLrc(
            listOf(
                WordByWordLine(
                    startMs = 1_230L,
                    endMs = 2_000L,
                    text = "hello",
                    segments = listOf(WordByWordSegment("hello", 1_230L, 2_000L))
                ),
                WordByWordLine(
                    startMs = 62_345L,
                    endMs = 63_000L,
                    text = "world",
                    segments = listOf(WordByWordSegment("world", 62_345L, 63_000L))
                )
            )
        )

        assertEquals("[00:01.23]hello\n[01:02.34]world", plainLrc)
    }

    @Test
    fun parseWordByWordImport_buildsPlainLrcWithSameTimestampTranslation() {
        val plainLrc = WordByWordLrcParser.parseImport(
            """
            [00:10.00]<00:10.00>君<00:10.20>の<00:10.40>背中
            [00:10.00]你的背影
            [00:12.00]<00:12.00>next
            [00:12.00]下一句
            """.trimIndent()
        ).plainLrc

        assertEquals("[00:10.00]君の背中 / 你的背影\n[00:12.00]next / 下一句", plainLrc)
    }

    @Test
    fun parseWordByWordImport_ignoresUnmatchedTranslationLine() {
        val plainLrc = WordByWordLrcParser.parseImport(
            """
            [00:10.00]<00:10.00>君<00:10.20>の<00:10.40>背中
            [00:11.00]不会被误合并的翻译
            """.trimIndent()
        ).plainLrc

        assertEquals("[00:10.00]君の背中", plainLrc)
    }

    @Test
    fun parseWordByWordImport_reportsTranslationPresence() {
        assertTrue(
            WordByWordLrcParser.parseImport(
                """
                [00:10.00]<00:10.00>君<00:10.20>の<00:10.40>背中
                [00:10.00]你的背影
                """.trimIndent()
            ).hasTranslation
        )
    }

    @Test
    fun validateWordByWordLrcForStorage_allowsSameTimestampTranslationLines() {
        val result = WordByWordLrcParser.validateForStorage(
            """
            [00:10.00]<00:10.00>君<00:10.20>の<00:10.40>背中
            [00:10.00]你的背影
            """.trimIndent()
        )

        assertTrue(result.isValid)
        assertEquals(emptyList<Int>(), result.invalidLineNumbers)
    }

    @Test
    fun validateWordByWordLrcForStorage_ignoresMetadataAndReportsInvalidLines() {
        val result = WordByWordLrcParser.validateForStorage(
            """
            [ar:Artist]
            not timed
            [00:10.00]<00:10.00>valid
            """.trimIndent()
        )

        assertEquals(false, result.isValid)
        assertEquals(listOf(2), result.invalidLineNumbers)
    }

    @Test
    fun validateWordByWordLrcForStorage_rejectsInvalidSecondValues() {
        val result = WordByWordLrcParser.validateForStorage(
            """
            [00:60.00]<00:60.00>bad
            [01:00.00]<01:00.00>good
            """.trimIndent()
        )

        assertEquals(false, result.isValid)
        assertEquals(listOf(1), result.invalidLineNumbers)
    }

    @Test
    fun parseWordByWordImport_preservesMetadataInPlainLrc() {
        val wordByWordLrc = """
            [ar:Artist]
            [ti:Title]
            [00:10.00]<00:10.00>君<00:10.20>の<00:10.40>背中
            [00:10.00]你的背影
        """.trimIndent()

        val parsedWordByWordLyrics = WordByWordLrcParser.parseImport(wordByWordLrc)

        assertEquals(listOf("[ar:Artist]", "[ti:Title]"), parsedWordByWordLyrics.metadataLines)
        assertEquals(
            "[ar:Artist]\n[ti:Title]\n[00:10.00]君の背中 / 你的背影",
            parsedWordByWordLyrics.plainLrc
        )
    }

    @Test
    fun wordByWordLinesToWordByWordLrc_preservesMetadata() {
        val wordByWordLines = listOf(
            WordByWordLine(
                startMs = 10_000L,
                endMs = 10_400L,
                text = "hi",
                segments = listOf(WordByWordSegment("hi", 10_000L, 10_400L))
            )
        )

        val wordByWordLrc = WordByWordLrcParser.wordByWordLinesToWordByWordLrc(
            wordByWordLines = wordByWordLines,
            metadataLines = listOf("[ar:Artist]", "[ti:Title]")
        )

        assertEquals("[ar:Artist]\n[ti:Title]\n[00:10.00]<00:10.00>hi", wordByWordLrc)
    }

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
        assertEquals(2, documentRoot.length())
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

    private fun assertSerializedLine(wordByWordLineJson: JSONObject) {
        assertEquals(4, wordByWordLineJson.length())
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
        assertEquals(3, wordByWordSegmentJson.length())
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
