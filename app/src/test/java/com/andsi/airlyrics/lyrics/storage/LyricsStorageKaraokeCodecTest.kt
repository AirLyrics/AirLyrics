package com.andsi.airlyrics.lyrics.storage

import com.andsi.airlyrics.lyrics.KaraokeLine
import com.andsi.airlyrics.lyrics.KaraokeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsStorageKaraokeCodecTest {
    @Test
    fun parseEnhancedLrcKaraoke_buildsWordTimingLines() {
        val lines = LyricsStorage.parseEnhancedLrcKaraokeForTest(
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
        val lines = LyricsStorage.parseEnhancedLrcKaraokeForTest(
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
        val plain = LyricsStorage.karaokeLinesToPlainLrcForTest(
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
        val plain = LyricsStorage.parseKaraokeImportPlainLrcForTest(
            """
            [00:10.00]<00:10.00>君<00:10.20>の<00:10.40>背中
            [00:10.00]你的背影
            [00:12.00]<00:12.00>next
            [00:12.00]下一句
            """.trimIndent()
        )

        assertEquals("[00:10.00]君の背中 / 你的背影\n[00:12.00]next / 下一句", plain)
    }

    @Test
    fun parseKaraokeImport_ignoresUnmatchedTranslationLine() {
        val plain = LyricsStorage.parseKaraokeImportPlainLrcForTest(
            """
            [00:10.00]<00:10.00>君<00:10.20>の<00:10.40>背中
            [00:11.00]不会被误合并的翻译
            """.trimIndent()
        )

        assertEquals("[00:10.00]君の背中", plain)
    }

    @Test
    fun parseKaraokeImport_reportsTranslationPresence() {
        assertTrue(
            LyricsStorage.parseKaraokeImportHasTranslationForTest(
                """
                [00:10.00]<00:10.00>君<00:10.20>の<00:10.40>背中
                [00:10.00]你的背影
                """.trimIndent()
            )
        )
    }


    @Test
    fun validateEnhancedLrcForStorage_allowsSameTimestampTranslationLines() {
        val result = KaraokeLyricsCodec.validateEnhancedLrcForStorage(
            """
            [00:10.00]<00:10.00>君<00:10.20>の<00:10.40>背中
            [00:10.00]你的背影
            """.trimIndent()
        )

        assertTrue(result.isValid)
        assertEquals(emptyList<Int>(), result.invalidLineNumbers)
    }


    @Test
    fun karaokeJsonRoundTrip_preservesValidLines() {
        val original = listOf(
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

        val json = LyricsStorage.karaokeLinesToJsonForTest(original)
        val parsed = LyricsStorage.parseKaraokeJsonForTest(json)

        assertEquals(original, parsed)
        assertTrue(json.contains("startMs"))
        assertTrue(json.contains("tokens"))
    }
}
