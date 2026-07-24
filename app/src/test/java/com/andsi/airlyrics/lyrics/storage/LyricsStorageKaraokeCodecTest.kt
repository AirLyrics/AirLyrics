package com.andsi.airlyrics.lyrics.storage

import com.andsi.airlyrics.lyrics.KaraokeLine
import com.andsi.airlyrics.lyrics.KaraokeToken
import com.andsi.airlyrics.lyrics.parser.KaraokeLrcParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsStorageKaraokeCodecTest {
    @Test
    fun parseEnhancedLrcKaraoke_buildsWordTimingLines() {
        val lines = KaraokeLrcParser.parse(
            """
            [00:10.00]<00:10.00>he<00:10.30>llo <00:10.60>world
            [00:12.00]<00:12.00>next
            """.trimIndent()
        )

        assertEquals(2, lines.size)
        assertEquals(10_000L, lines[0].startMs)
        assertEquals(12_000L, lines[0].endMs)
        assertEquals("hello world", lines[0].text)
        assertEquals(listOf("he", "llo ", "world"), lines[0].tokens.map { it.text })
        assertEquals(listOf(10_000L, 10_300L, 10_600L), lines[0].tokens.map { it.startMs })
        assertEquals(12_000L, lines[0].tokens.last().endMs)
    }

    @Test
    fun parseEnhancedLrcKaraoke_ignoresInvalidOrUntimedLines() {
        val lines = KaraokeLrcParser.parse(
            """
            plain text
            [00:01.00]no word tags
            [00:02.00]<00:01.50>starts before line
            [00:03.00]<00:03.00>valid
            """.trimIndent()
        )

        assertEquals(1, lines.size)
        assertEquals("valid", lines.single().text)
    }

    @Test
    fun karaokeLinesToPlainLrc_preservesLineTextAndTiming() {
        val plain = KaraokeLrcParser.linesToPlainLrc(
            listOf(
                KaraokeLine(
                    startMs = 1_230L,
                    endMs = 2_000L,
                    text = "hello",
                    tokens = listOf(KaraokeToken("hello", 1_230L, 2_000L))
                ),
                KaraokeLine(
                    startMs = 62_345L,
                    endMs = 63_000L,
                    text = "world",
                    tokens = listOf(KaraokeToken("world", 62_345L, 63_000L))
                )
            )
        )

        assertEquals("[00:01.23]hello\n[01:02.34]world", plain)
    }

    @Test
    fun parseKaraokeImport_buildsPlainLrcWithSameTimestampTranslation() {
        val plain = KaraokeLrcParser.parseImport(
            """
            [00:10.00]<00:10.00>君<00:10.20>の<00:10.40>背中
            [00:10.00]你的背影
            [00:12.00]<00:12.00>next
            [00:12.00]下一句
            """.trimIndent()
        ).plainLrc

        assertEquals("[00:10.00]君の背中 / 你的背影\n[00:12.00]next / 下一句", plain)
    }

    @Test
    fun parseKaraokeImport_ignoresUnmatchedTranslationLine() {
        val plain = KaraokeLrcParser.parseImport(
            """
            [00:10.00]<00:10.00>君<00:10.20>の<00:10.40>背中
            [00:11.00]不会被误合并的翻译
            """.trimIndent()
        ).plainLrc

        assertEquals("[00:10.00]君の背中", plain)
    }

    @Test
    fun parseKaraokeImport_reportsTranslationPresence() {
        assertTrue(
            KaraokeLrcParser.parseImport(
                """
                [00:10.00]<00:10.00>君<00:10.20>の<00:10.40>背中
                [00:10.00]你的背影
                """.trimIndent()
            ).hasTranslation
        )
    }

    @Test
    fun validateEnhancedLrcForStorage_allowsSameTimestampTranslationLines() {
        val result = KaraokeLrcParser.validateForStorage(
            """
            [00:10.00]<00:10.00>君<00:10.20>の<00:10.40>背中
            [00:10.00]你的背影
            """.trimIndent()
        )

        assertTrue(result.isValid)
        assertEquals(emptyList<Int>(), result.invalidLineNumbers)
    }

    @Test
    fun validateEnhancedLrcForStorage_ignoresMetadataAndReportsInvalidLines() {
        val result = KaraokeLrcParser.validateForStorage(
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
    fun validateEnhancedLrcForStorage_rejectsInvalidSecondValues() {
        val result = KaraokeLrcParser.validateForStorage(
            """
            [00:60.00]<00:60.00>bad
            [01:00.00]<01:00.00>good
            """.trimIndent()
        )

        assertEquals(false, result.isValid)
        assertEquals(listOf(1), result.invalidLineNumbers)
    }

    @Test
    fun parseKaraokeImport_preservesMetadataInPlainLrc() {
        val raw = """
            [ar:Artist]
            [ti:Title]
            [00:10.00]<00:10.00>君<00:10.20>の<00:10.40>背中
            [00:10.00]你的背影
        """.trimIndent()

        val parsed = KaraokeLrcParser.parseImport(raw)

        assertEquals(listOf("[ar:Artist]", "[ti:Title]"), parsed.metadataLines)
        assertEquals(
            "[ar:Artist]\n[ti:Title]\n[00:10.00]君の背中 / 你的背影",
            parsed.plainLrc
        )
    }

    @Test
    fun karaokeLinesToEnhancedLrc_preservesMetadata() {
        val lines = listOf(
            KaraokeLine(
                startMs = 10_000L,
                endMs = 10_400L,
                text = "hi",
                tokens = listOf(KaraokeToken("hi", 10_000L, 10_400L))
            )
        )

        val enhancedLrc = KaraokeLrcParser.linesToEnhancedLrc(
            lines = lines,
            metadataLines = listOf("[ar:Artist]", "[ti:Title]")
        )

        assertEquals("[ar:Artist]\n[ti:Title]\n[00:10.00]<00:10.00>hi", enhancedLrc)
    }

    @Test
    fun karaokeJsonParser_readsFixedLegacyArrayFixture() {
        val document = KaraokeLyricsCodec.parseDocumentJson(LEGACY_KARAOKE_JSON_FIXTURE)

        assertEquals(emptyList<String>(), document.metadataLines)
        assertEquals(
            listOf(
                KaraokeLine(
                    startMs = 5_000L,
                    endMs = 6_000L,
                    text = "hi",
                    tokens = listOf(
                        KaraokeToken("h", 5_000L, 5_300L),
                        KaraokeToken("i", 5_300L, 6_000L)
                    )
                )
            ),
            document.lines
        )
    }

    @Test
    fun karaokeJsonSerializer_writesRequiredPersistentSchemasAndValues() {
        val lines = listOf(
            KaraokeLine(
                startMs = 5_000L,
                endMs = 6_000L,
                text = "hi",
                tokens = listOf(
                    KaraokeToken("h", 5_000L, 5_300L),
                    KaraokeToken("i", 5_300L, 6_000L)
                )
            )
        )
        val metadataLines = listOf("[ar:Artist]", "[ti:Title]")

        val legacyRoot = JSONArray(KaraokeLyricsCodec.linesToJson(lines))
        assertEquals(1, legacyRoot.length())
        assertSerializedLine(legacyRoot.getJSONObject(0))

        val documentRoot = JSONObject(KaraokeLyricsCodec.linesToJson(lines, metadataLines))
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
    fun karaokeJsonRoundTrip_preservesSpecialTextSemantics() {
        val specialText = "He said \"歌\" at C:\\Lyrics\n日本語與繁體中文"
        val original = listOf(
            KaraokeLine(
                startMs = 7_000L,
                endMs = 8_000L,
                text = specialText,
                tokens = listOf(
                    KaraokeToken(
                        text = specialText,
                        startMs = 7_000L,
                        endMs = 8_000L
                    )
                )
            )
        )

        val parsed = KaraokeLyricsCodec.parseJson(
            KaraokeLyricsCodec.linesToJson(original)
        )

        assertEquals(original, parsed)
    }

    private fun assertSerializedLine(line: JSONObject) {
        assertEquals(4, line.length())
        assertEquals(5_000L, line.getLong("startMs"))
        assertEquals(6_000L, line.getLong("endMs"))
        assertEquals("hi", line.getString("text"))

        val tokens = line.getJSONArray("tokens")
        assertEquals(2, tokens.length())
        assertSerializedToken(tokens.getJSONObject(0), "h", 5_000L, 5_300L)
        assertSerializedToken(tokens.getJSONObject(1), "i", 5_300L, 6_000L)
    }

    private fun assertSerializedToken(
        token: JSONObject,
        expectedText: String,
        expectedStartMs: Long,
        expectedEndMs: Long
    ) {
        assertEquals(3, token.length())
        assertEquals(expectedText, token.getString("text"))
        assertEquals(expectedStartMs, token.getLong("startMs"))
        assertEquals(expectedEndMs, token.getLong("endMs"))
    }

    private companion object {
        val LEGACY_KARAOKE_JSON_FIXTURE = """
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
