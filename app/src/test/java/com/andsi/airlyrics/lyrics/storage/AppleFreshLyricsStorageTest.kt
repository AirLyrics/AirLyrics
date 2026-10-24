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
class AppleFreshLyricsStorageTest : LyricsStorageTestBase() {
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
        assertTrue(LyricsStorage.savePlainLyrics(context, "彩虹", "周杰伦", 263787, com.andsi.airlyrics.lyrics.parser.LrcParser.mergeOriginalAndTranslationForStorage(result.plainLrc, result.translatedLrc), plainProvider = "apple"))
        assertTrue(LyricsStorage.saveWordByWordLyrics(context, "彩虹", "周杰伦", 263787, result.wordByWordLines, wordByWordProvider = "apple"))
        val stored = LyricsStorage.readWordByWordLyrics(context, "彩虹", "周杰伦", 263787)
        assertEquals(result.wordByWordLines, stored)
        val plain = requireNotNull(LyricsStorage.readPlainLyrics(context, "彩虹", "周杰伦", 263787))
        assertTrue(stored.any { it.text.contains("(睡得著)") })
        assertTrue(plain.contains("(睡得著)"))
        stored.forEach { line ->
            assertTrue(line.translatedSegments.isNotEmpty())
            assertEquals(line.translatedText, line.translatedSegments.joinToString("") { it.text })
        }
        stored.filter { it.text.contains('(') }.forEach { line ->
            assertEquals(line.text.replace(" ", ""), line.segments.joinToString("") { it.text }.replace(" ", ""))
            assertEquals(line.translatedText, line.translatedSegments.joinToString("") { it.text })
            val view = TextView(context)
            val renderer = FloatingLyricsRenderer(
                textViewProvider = { view }, contentModeProvider = { LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION },
                lineModeProvider = { LyricsLineDisplayMode.CURRENT_ONLY }, switchAnimationModeProvider = { LyricsSwitchAnimationMode.NONE },
                wordByWordLyricsEnabledProvider = { true }, wordByWordHighlightColorProvider = { Color.MAGENTA },
                scriptPreferenceProvider = { LyricsScriptPreference.NONE }, noTranslationTextProvider = { "" })
            val mapper = FloatingLyricsRenderer::class.java.getDeclaredMethod(
                "mapDisplaySegments", String::class.java, String::class.java, List::class.java
            ).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val mapped = mapper.invoke(renderer, line.segments.joinToString("") { it.text }, line.text, line.segments)
                as List<com.andsi.airlyrics.lyrics.WordByWordSegment>
            assertEquals(line.text, mapped.joinToString("") { it.text })
            assertEquals(line.segments.map { it.startMs to it.endMs }, mapped.map { it.startMs to it.endMs })
            renderer.parseAndShow(plain, null, stored, "")
            renderer.updatePlayback(line.startMs + 500, true)
            renderer.tick()
            val rendered = view.text as Spanned
            if (line.text.contains("(睡得著)")) assertTrue(rendered.toString().contains("(睡得著)"))
            val secondaryStart = rendered.indexOf('\n') + 1
            assertTrue(rendered.getSpans(0, rendered.length, ForegroundColorSpan::class.java)
                .any { rendered.getSpanStart(it) >= secondaryStart })
        }
    }

    @Test fun freshNativeEchoRenderingAndStorage() {
        val result = requireNotNull(ApplePlainLyricsProvider.parse(
            requireNotNull(javaClass.getResource("/apple/rainbow-native-fresh.json")).readText(),
            "", "", "", 0))
        val line = result.wordByWordLines.first { it.text == "看不見妳的笑 我怎麼睡得著 (睡得著)" }
        assertEquals("看不見妳的笑 我怎麼睡得著(睡得著)", line.segments.joinToString("") { it.text })
        assertTrue(LyricsStorage.saveWordByWordLyrics(context, "fresh", "jay", 263787, result.wordByWordLines, wordByWordProvider = "apple"))
        val stored = LyricsStorage.readWordByWordLyrics(context, "fresh", "jay", 263787)
        assertEquals(result.wordByWordLines, stored)
        val plain = com.andsi.airlyrics.lyrics.parser.LrcParser.mergeOriginalAndTranslationForStorage(result.plainLrc, result.translatedLrc)
        assertTrue(LyricsStorage.savePlainLyrics(context, "fresh", "jay", 263787, plain, plainProvider = "apple"))
        val storedPlain = requireNotNull(LyricsStorage.readPlainLyrics(context, "fresh", "jay", 263787))
        for (line in stored.filter { it.text.contains('(') }) {
            assertTrue(storedPlain.contains(line.text))
            assertTrue(storedPlain.contains(line.translatedText))
            for (preference in listOf(LyricsScriptPreference.NONE, LyricsScriptPreference.SIMPLIFIED)) {
                val view = TextView(context)
                val renderer = FloatingLyricsRenderer(
                    textViewProvider = { view }, contentModeProvider = { LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION },
                    lineModeProvider = { LyricsLineDisplayMode.CURRENT_ONLY }, switchAnimationModeProvider = { LyricsSwitchAnimationMode.NONE },
                    wordByWordLyricsEnabledProvider = { true }, wordByWordHighlightColorProvider = { Color.MAGENTA },
                    scriptPreferenceProvider = { preference }, noTranslationTextProvider = { "" })
                val mapper = FloatingLyricsRenderer::class.java.getDeclaredMethod(
                    "mapDisplaySegments", String::class.java, String::class.java, List::class.java
                ).apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val mapped = mapper.invoke(renderer, line.segments.joinToString("") { it.text }, line.text, line.segments)
                    as List<com.andsi.airlyrics.lyrics.WordByWordSegment>
                assertEquals(line.text, mapped.joinToString("") { it.text })
                assertEquals(line.segments.map { it.startMs to it.endMs }, mapped.map { it.startMs to it.endMs })
                renderer.parseAndShow(storedPlain, null, stored, "")
                val token = line.segments.last()
                val nextStart = stored.firstOrNull { it.startMs > line.startMs }?.startMs ?: Long.MAX_VALUE
                renderer.updatePlayback(minOf((token.startMs + token.endMs) / 2, nextStart - 20), true)
                renderer.tick()
                val rendered = view.text as Spanned
                val spans = rendered.getSpans(0, rendered.length, ForegroundColorSpan::class.java)
                println("FRESH $preference rendered=[$rendered] spans=" + spans.joinToString { "${rendered.getSpanStart(it)}..${rendered.getSpanEnd(it)}" })
                val expected = if (preference == LyricsScriptPreference.NONE) line.text + "\n" + line.translatedText else line.translatedText
                assertEquals(expected, rendered.toString())
                assertTrue("primary must highlight", spans.any { rendered.getSpanStart(it) == 0 })
                assertTrue("echo must highlight", spans.any {
                    rendered.getSpanStart(it) < rendered.toString().substringBefore('\n').length &&
                        rendered.getSpanEnd(it) > rendered.toString().indexOf('(')
                })
                if (preference == LyricsScriptPreference.NONE) {
                    assertTrue("translation must highlight", spans.any { rendered.getSpanStart(it) == line.text.length + 1 })
                }
            }
        }
    }

}
