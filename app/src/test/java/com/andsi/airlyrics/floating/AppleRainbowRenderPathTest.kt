package com.andsi.airlyrics.floating

import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.core.model.LyricsScriptPreference
import com.andsi.airlyrics.core.model.LyricsSettings
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.core.text.ChineseText
import com.andsi.airlyrics.lyrics.LyricsLookupCancellationToken
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.LyricsRepository
import com.andsi.airlyrics.lyrics.PlainLyricsProvider
import com.andsi.airlyrics.lyrics.PlainLyricsSearchRequest
import com.andsi.airlyrics.lyrics.providers.ApplePlainLyricsProvider
import com.andsi.airlyrics.lyrics.search.ManualLyricsSearch
import com.andsi.airlyrics.lyrics.storage.LyricsStorageTestBase
import com.andsi.airlyrics.media.PlaybackClock
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppleRainbowRenderPathTest : LyricsStorageTestBase() {
    @After
    fun resetDictionaries() = ChineseText.resetForTest()

    @Test
    fun selectedNativeLyricsKeepEchoTextAndHighlightAfterRepositoryRead() {
        ChineseText.resetForTest()
        ChineseText.loadFromDirectory(File("src/main/assets/opencc"))
        val song = SongIdentity("彩虹", "周杰伦", durationMs = 263_787)
        val json = requireNotNull(javaClass.getResource("/apple/rainbow-native.json")).readText()
        val nativeLyrics = requireNotNull(
            ApplePlainLyricsProvider.parse(json, song.title, song.artist, song.album, song.durationMs),
        )
        assertTrue(
            ManualLyricsSearch(context).apply(
                song,
                nativeLyrics,
                LyricsLookupCancellationToken(song.storageKey(), 1L),
                approvedRevision = null,
            ),
        )

        var onlineCalls = 0
        val provider = object : PlainLyricsProvider {
            override val id = "apple"
            override val name = "Apple Music"
            override val supportsWordTiming = true
            override fun cached(request: PlainLyricsSearchRequest): LyricsProviderResult? {
                onlineCalls++
                return null
            }
            override fun fetch(request: PlainLyricsSearchRequest): Result<LyricsProviderResult?> {
                onlineCalls++
                return Result.success(null)
            }
        }
        val result = requireNotNull(
            LyricsRepository.findLyricsWithProviders(
                context = context,
                onlinePlainLyricsProviders = mapOf(PlainLyricsSearchSource.APPLE to provider),
                settings = LyricsSettings(
                    listOf(PlainLyricsSearchSource.APPLE),
                    autoSearchOnline = true,
                    autoSaveLocal = true,
                    wordByWordLyricsEnabled = true,
                ),
                title = song.title,
                artist = song.artist,
                durationMs = song.durationMs,
            ).getOrThrow(),
        )
        assertTrue(result.selectedManually)
        assertEquals("Local selection should skip online provider lookup", 0, onlineCalls)
        assertEchoRendering(result)
    }

    private fun assertEchoRendering(result: LyricsProviderResult) {

        val original = "看不見妳的笑 我怎麼睡得著 (睡得著)"
        val translation = "看不见你的笑我怎么睡得著 (睡得著)"
        val scenarios = listOf(
            LyricsScriptPreference.NONE to listOf(original, translation),
            LyricsScriptPreference.SIMPLIFIED to listOf(translation),
        )
        val playbackClock = PlaybackClock { 0L }
        playbackClock.update(148_100L, isPlaying = false)

        for ((script, expectedLines) in scenarios) {
            val view = TextView(context)
            val renderer = FloatingLyricsRenderer(
                playbackClock = playbackClock,
                textViewProvider = { view },
                contentModeProvider = { LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION },
                scriptPreferenceProvider = { script },
                lineModeProvider = { LyricsLineDisplayMode.CURRENT_ONLY },
                wordByWordLyricsEnabledProvider = { true },
            )
            renderer.parseAndShow(result.plainLrc, result.translatedLrc, result.wordByWordLines, "empty")

            val text = view.text as Spanned
            assertEquals(script.name, expectedLines.joinToString("\n"), text.toString())
            val spans = text.getSpans(0, text.length, ForegroundColorSpan::class.java)
            assertTrue(script.name, spans.all { it.foregroundColor != view.currentTextColor })
            val ranges = spans.mapTo(mutableListOf()) { text.getSpanStart(it) to text.getSpanEnd(it) }
            ranges.sortBy { (start, _) -> start }
            var lineStart = 0
            val expectedRanges = buildList {
                for (line in expectedLines) {
                    val activeEchoCharacter = lineStart + line.lastIndexOf('著')
                    add(lineStart to activeEchoCharacter)
                    add(activeEchoCharacter to (activeEchoCharacter + 1))
                    lineStart += line.length + 1
                }
            }
            assertEquals(script.name, expectedRanges, ranges)
        }
    }
}
