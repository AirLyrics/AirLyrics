package com.andsi.airlyrics.floating

import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import com.andsi.airlyrics.core.model.*
import com.andsi.airlyrics.core.text.ChineseText
import com.andsi.airlyrics.lyrics.*
import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.lyrics.providers.ApplePlainLyricsProvider
import com.andsi.airlyrics.lyrics.storage.*
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Offline production-path reproduction using the unmodified native response for 1721455866. */
@RunWith(RobolectricTestRunner::class)
class AppleRainbowRenderPathTest : LyricsStorageTestBase() {
    private val song = SongIdentity("彩虹", "周杰伦", durationMs = 264_000)
    private lateinit var fresh: LyricsProviderResult

    @Before
    fun loadPayload() {
        ChineseText.resetForTest()
        ChineseText.loadFromDirectory(File("src/main/assets/opencc"))
        fresh = requireNotNull(ApplePlainLyricsProvider.parse(
            requireNotNull(javaClass.getResource("/apple/rainbow-native.json")).readText(),
            song.title, song.artist, "", song.durationMs))
    }

    @After
    fun clearDictionaries() = ChineseText.resetForTest()

    @Test
    fun realPayload_highlightsThroughFreshCodecStorageAndRepositoryPaths() {
        val target = fresh.wordByWordLines.first { it.startMs == 63_849L }
        assertEquals(7, target.translatedSegments.size)
        assertEquals("看不见你的笑我怎么睡得著", target.translatedSegments.joinToString("") { it.text })
        val progress = wordByWordHighlightProgress(target, target.translatedText, 66_400,
            target.translatedSegments)
        assertEquals(6, progress.completedEnd)
        assertEquals(6, progress.activeStart)
        assertEquals(7, progress.activeEnd)
        assertTrue(progress.activeFraction > 0f)
        assertEquals(13, target.text.length)
        assertEquals(12, target.translatedText.length)
        checkRendering("fresh", fresh, expectedHighlight = true)
        val words = WordByWordLyricsJsonCodec.parseWordByWordLinesJson(
            WordByWordLyricsJsonCodec.wordByWordLinesToJson(fresh.wordByWordLines))
        assertEquals(fresh.wordByWordLines, words)
        checkRendering("codec", fresh.copy(wordByWordLines = words), true)

        assertTrue(LyricsStorage.saveWordByWordLyrics(context, song.title, song.artist,
            song.durationMs, words, wordByWordProvider = "apple"))
        assertEquals(words, LyricsStorage.readWordByWordLyrics(context, song.title, song.artist, song.durationMs))
        checkRendering("merged LRC", fresh.copy(
            plainLrc = LrcParser.mergeOriginalAndTranslationForStorage(fresh.plainLrc, fresh.translatedLrc),
            translatedLrc = null,
            wordByWordLines = LyricsStorage.readWordByWordLyrics(context, song.title, song.artist, song.durationMs)), true)

        ManualLyricsSelectionStore.commit(context, song, LyricsSearchMode.Original, fresh, true, true)
        val selected = requireNotNull(ManualLyricsSelectionStore.read(context, song)).result
        assertEquals(fresh.wordByWordLines, selected.wordByWordLines)
        assertEquals(fresh.plainLrc, selected.plainLrc)
        assertEquals(fresh.translatedLrc, selected.translatedLrc)
        checkRendering("manual selection", selected, true)
        checkRendering("repository manual selection", automaticLookup(), true)

        ManualLyricsSelectionStore.forget(context, song)
        checkRendering("repository ordinary saved LRC", automaticLookup(), true)
    }

    private fun automaticLookup(): LyricsProviderResult {
        var calls = 0
        val provider = object : PlainLyricsProvider {
            override val id = "apple"
            override val name = "Apple Music"
            override val supportsWordTiming = true
            override fun cached(request: PlainLyricsSearchRequest): LyricsProviderResult {
                calls++
                return fresh
            }
            override fun fetch(request: PlainLyricsSearchRequest): Result<LyricsProviderResult?> {
                calls++
                return Result.success(fresh)
            }
        }
        val result = requireNotNull(LyricsRepository.findLyricsWithProviders(
            context, mapOf(PlainLyricsSearchSource.APPLE to provider),
            LyricsSettings(listOf(PlainLyricsSearchSource.APPLE), true, true, wordByWordLyricsEnabled = true),
            song.title, song.artist, durationMs = song.durationMs).getOrThrow())
        assertEquals("saved lyrics must prevent both cache lookup and network fetch", 0, calls)
        return result
    }

    private fun checkRendering(path: String, result: LyricsProviderResult, expectedHighlight: Boolean) {
        // First occurrence, its neighbour, and the repeat with the parenthetical echo.
        for (position in listOf(66_400L, 72_000L, 144_200L)) {
            for (preference in listOf(LyricsScriptPreference.NONE, LyricsScriptPreference.SIMPLIFIED)) {
                val view = TextView(context)
                val renderer = FloatingLyricsRenderer(
                    textViewProvider = { view },
                    contentModeProvider = { LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION },
                    scriptPreferenceProvider = { preference },
                    wordByWordLyricsEnabledProvider = { true })
                renderer.updatePlayback(position, false)
                renderer.parseAndShow(result.plainLrc, result.translatedLrc,
                    result.wordByWordLines, "empty")
                renderer.tick()
                val text = view.text as Spanned
                val start = if (preference == LyricsScriptPreference.NONE) text.toString().indexOf('\n') + 1 else 0
                val ranges = text.getSpans(0, text.length, ForegroundColorSpan::class.java)
                    .map { text.getSpanStart(it) to text.getSpanEnd(it) }
                val highlight = ranges.any { it.first >= start && it.second > start }
                println("RAINBOW path=$path position=$position script=$preference text=${text.toString().replace('\n', '|')} spans=$ranges")
                assertTrue("missing translation: $text", text.contains(if (position == 72_000L) "你的声音" else "看不见"))
                assertEquals("$path position=$position script=$preference", expectedHighlight || position != 66_400L, highlight)
                assertTrue(ranges.all { it.first >= 0 && it.second <= text.length })
            }
        }
    }
}
