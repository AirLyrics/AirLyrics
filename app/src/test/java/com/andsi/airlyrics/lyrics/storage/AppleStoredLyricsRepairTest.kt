package com.andsi.airlyrics.lyrics.storage

import android.graphics.Color
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import com.andsi.airlyrics.core.model.*
import com.andsi.airlyrics.core.text.ChineseText
import com.andsi.airlyrics.floating.FloatingLyricsRenderer
import com.andsi.airlyrics.lyrics.providers.ApplePlainLyricsProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppleStoredLyricsRepairTest : LyricsStorageTestBase() {
    @Before fun loadDictionaries() {
        ChineseText.resetForTest()
        ChineseText.loadFromDirectory(java.io.File("src/main/assets/opencc"))
    }

    @After fun resetDictionaries() {
        ChineseText.resetForTest()
    }

    private fun payload() = requireNotNull(javaClass.getResource("/apple/rainbow-tr-zh-Hans.json")).readText()
    private fun parsed() = requireNotNull(ApplePlainLyricsProvider.parse(payload(), "", "", "", 0))

    @Test fun historicalFlatSpanRegexDropsFirstBackgroundToken() {
        val ttml = requireNotNull(javaClass.getResource("/apple/rainbow-tr-zh-Hans.ttml")).readText()
        val paragraphs = Regex("<p\\b[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
        val flatSpans = Regex("<span\\b([^>]*)>(.*?)</span>", RegexOption.DOT_MATCHES_ALL)
        val broken = paragraphs.findAll(ttml).map { paragraph ->
            flatSpans.findAll(paragraph.groupValues[1]).filter {
                it.groupValues[1].contains("begin=") && it.groupValues[1].contains("end=")
            }.joinToString("") { it.groupValues[2].replace(Regex("<[^>]+>"), "").trim() }
        }.toList()
        for (ending in listOf("著著)", "到到)", "繞是會繞)", "走自己走)", "單很簡單)", "礙的阻礙)")) {
            assertTrue(ending, broken.any { it.endsWith(ending) })
        }
    }

    @Test fun freshPayloadSurvivesSaveReadAndRender() {
        ChineseText.ensureLoaded(context)
        val result = parsed()
        assertEquals(39, result.wordByWordLines.size)
        assertTrue(LyricsStorage.savePlainLyrics(context, "彩虹", "周杰伦", 263787, result.plainLrc, plainProvider = "apple"))
        assertTrue(LyricsStorage.saveWordByWordLyrics(context, "彩虹", "周杰伦", 263787, result.wordByWordLines, wordByWordProvider = "apple"))
        val stored = LyricsStorage.readWordByWordLyrics(context, "彩虹", "周杰伦", 263787)
        assertEquals(result.wordByWordLines, stored)
        stored.filter { it.text.contains('(') }.forEach { line ->
            assertFalse(LyricsStorage.hasLostEchoBracket(line.segments.joinToString("") { it.text }))
            assertEquals(line.translatedText, line.translatedSegments.joinToString("") { it.text })
            val view = TextView(context)
            val renderer = FloatingLyricsRenderer(
                textViewProvider = { view }, contentModeProvider = { LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION },
                lineModeProvider = { LyricsLineDisplayMode.CURRENT_ONLY }, switchAnimationModeProvider = { LyricsSwitchAnimationMode.NONE },
                wordByWordLyricsEnabledProvider = { true }, wordByWordHighlightColorProvider = { Color.MAGENTA },
                scriptPreferenceProvider = { LyricsScriptPreference.NONE }, noTranslationTextProvider = { "" })
            // Exercise v75 fallback too: old documents had no translated tokens.
            renderer.parseAndShow(result.plainLrc, result.translatedLrc, listOf(line.copy(translatedSegments = emptyList())), "")
            renderer.updatePlayback(line.startMs + 500, true)
            renderer.tick()
            val rendered = view.text as Spanned
            val secondaryStart = rendered.indexOf('\n') + 1
            assertTrue(rendered.getSpans(0, rendered.length, ForegroundColorSpan::class.java)
                .any { rendered.getSpanStart(it) >= secondaryStart })
        }
    }

    @Test fun recoversConversionTimingFromSavedPlainLrc() {
        ChineseText.ensureLoaded(context)
        val result = parsed()
        val plain = com.andsi.airlyrics.lyrics.parser.LrcParser.mergeOriginalAndTranslationForStorage(
            result.plainLrc, result.translatedLrc)
        assertTrue(LyricsStorage.savePlainLyrics(context, "legacy", "周杰伦", 263787, plain, plainProvider = "apple"))
        val legacy = result.wordByWordLines.map { it.copy(translatedText = "", translatedSegments = emptyList()) }
        assertTrue(LyricsStorage.saveWordByWordLyrics(context, "legacy", "周杰伦", 263787, legacy, wordByWordProvider = "apple"))
        val read = LyricsStorage.readWordByWordLyrics(context, "legacy", "周杰伦", 263787)
        assertEquals(legacy.map { it.segments }, read.map { it.segments })
        read.forEach {
            assertTrue(it.translatedText.isNotBlank())
            assertEquals(it.translatedText, it.translatedSegments.joinToString("") { token -> token.text })
        }
    }

    @Test fun legacyEchoIsRepairedWithoutDeletingSelectionOrImport() {
        ChineseText.ensureLoaded(context)
        val fresh = parsed().wordByWordLines.filter { it.text.contains('(') }
        val legacy = fresh.map { line ->
            val tokens = line.segments.filterNot { it.text.contains('(') }
            line.copy(text = tokens.joinToString("") { it.text }, segments = tokens, translatedSegments = emptyList())
        }
        assertTrue(legacy.first().text.endsWith("著著)"))
        for (source in listOf(LyricsStorage.SOURCE_DOWNLOADED, LyricsStorage.SOURCE_MANUAL_IMPORT)) {
            assertTrue(LyricsStorage.savePlainLyrics(context, source, "周杰伦", 263787, "[02:21.70]${legacy.first().text}",
                plainSource = source, plainProvider = "apple"))
            assertTrue(LyricsStorage.saveWordByWordLyrics(context, source, "周杰伦", 263787, legacy,
                wordByWordSource = source, wordByWordProvider = "apple"))
            val read = LyricsStorage.readWordByWordLyrics(context, source, "周杰伦", 263787)
            if (source == LyricsStorage.SOURCE_MANUAL_IMPORT) {
                assertEquals(legacy, read)
            } else {
                assertEquals(legacy.size, read.size)
                read.forEach {
                    assertFalse(LyricsStorage.hasLostEchoBracket(it.text))
                    assertTrue(it.translatedSegments.isNotEmpty())
                    assertEquals(it.translatedText, it.translatedSegments.joinToString("") { token -> token.text })
                }
                assertEquals(read, LyricsStorage.readWordByWordLyrics(context, source, "周杰伦", 263787))
                assertFalse(LyricsStorage.readPlainLyrics(context, source, "周杰伦", 263787)!!.contains("著著)"))
            }
        }
    }
}
