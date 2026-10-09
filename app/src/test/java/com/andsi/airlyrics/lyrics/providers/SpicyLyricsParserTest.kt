package com.andsi.airlyrics.lyrics.providers

import com.andsi.airlyrics.lyrics.LyricsPrecision
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic content only: never check API responses or their key-specific marks into Git. */
internal fun spicyFixture(type: String = "Syllable", source: String = "spicy_lyrics"): String = """
{"ok":true,"track":{"id":"synthetic","title":"Test","artist":"Artist","duration_ms":10000},
 "response":{"Status":200,"Body":{"id":"synthetic","Type":"$type","source":"$source","privateMark":"preserve-me",
 "UploadAttribution":{"Uploader":{"username":"Uploader","url":"https://example.com/u"},"Maker":{"username":"Maker","url":"https://example.com/m"}},
 "Content":[{"Type":"Vocal","StartTime":1.001,"EndTime":3.789,"Text":"Hello world",
 "Lead":{"StartTime":1.001,"EndTime":3.789,"Syllables":[
 {"Text":"Hel","StartTime":1.001,"EndTime":1.432,"IsPartOfWord":true},
 {"Text":"lo","StartTime":1.567,"EndTime":2.111,"IsPartOfWord":false},
 {"Text":"world","StartTime":2.444,"EndTime":3.789}]}}]}}}
""".trimIndent()

class SpicyLyricsParserTest {
    @Test fun syllablesDeriveWholeWordsAndKeepPreciseGapsAndCredits() {
        val value = SpicyLyricsParser.parse(spicyFixture(), "key", 100)!!
        assertEquals(LyricsPrecision.WORD, value.precision)
        assertEquals("[00:01.001]Hello world", value.plainLrc)
        assertEquals(1432L, value.wordByWordLines.single().segments.first().endMs)
        assertEquals(1567L, value.wordByWordLines.single().segments[1].startMs)
        assertEquals(listOf("Uploader", "Maker"), value.access!!.attribution.credits.map { it.name })
        assertFalse(value.access.allowExport)
        assertEquals(100 + SpicyLyricsParser.RETENTION_MS, value.access.expiresAtMs)
    }
    @Test fun lineOnlyAndOtherProvidersRetainRestriction() {
        val line = SpicyLyricsParser.parse(spicyFixture("Line", "apple_music"), "key", 100)!!
        assertEquals(LyricsPrecision.LINE, line.precision)
        assertEquals("Apple Music", line.access!!.attribution.provider)
        assertFalse(line.access.allowExport)
        assertTrue(line.access.attribution.credits.isEmpty())
        assertEquals("Unknown", SpicyLyricsParser.parse(spicyFixture(source = "future"), "key", 100)!!.access!!.attribution.provider)
    }
    @Test fun backgroundVoiceIsPreservedSeparatelyFromOrdinaryLine() {
        val raw = JSONObject(spicyFixture())
        val line = raw.getJSONObject("response").getJSONObject("Body").getJSONArray("Content").getJSONObject(0)
        val background = JSONObject(line.getJSONObject("Lead").toString())
        background.getJSONArray("Syllables").getJSONObject(0).put("Text", "Back")
        line.put("Background", org.json.JSONArray().put(background))
        val result = SpicyLyricsParser.parse(raw.toString(), "key", 100)!!
        assertEquals(2, result.wordByWordLines.size)
        assertFalse(result.wordByWordLines.first().isBackground)
        assertTrue(result.wordByWordLines.last().isBackground)
        assertEquals("[00:01.001]Hello world", result.plainLrc)
        assertNull(result.translatedLrc)
    }
    @Test fun unsupportedAndNotFoundAreMisses() {
        assertNull(SpicyLyricsParser.parse(spicyFixture("Static"), "key", 100))
        assertNull(SpicyLyricsParser.parse("""{"ok":false,"error_type":"NotFound"}""", "key", 100))
    }
    @Test fun invalidTimingAndWrongIdentityAreRejected() {
        for (raw in listOf(spicyFixture().replace("1.432", "0.5"), spicyFixture().replace("3.789", "30.0"))) {
            assertThrows(IllegalArgumentException::class.java) { SpicyLyricsParser.parse(raw, "key", 100) }
        }
        val raw = JSONObject(spicyFixture()).apply { getJSONObject("track").put("id", "wrong") }
        assertThrows(IllegalArgumentException::class.java) { SpicyLyricsParser.parse(raw.toString(), "key", 100) }
    }
}
