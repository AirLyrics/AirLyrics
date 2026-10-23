package com.andsi.airlyrics.floating

import android.animation.TimeInterpolator
import android.content.Context
import android.text.style.URLSpan
import com.andsi.airlyrics.core.model.LyricsAttribution
import com.andsi.airlyrics.core.model.LyricsCredit
import com.andsi.airlyrics.lyrics.LyricsAccess
import android.graphics.Color
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.core.model.LyricsScriptPreference
import com.andsi.airlyrics.core.text.ChineseText
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FloatingLyricsRendererTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun scriptMappedTokens_preserveWaitingAndOriginalTiming() {
        ChineseText.installTableForTest(mapOf('愛' to '爱', '說' to '说'))
        val view = TextView(context)
        val renderer = renderer(textView = view, wordByWordEnabled = true)
        renderer.parseAndShow(
            plainLrc = "[00:01.00]爱说",
            wordByWordLines = listOf(WordByWordLine(1_000L, 12_000L, "愛說", listOf(
                WordByWordSegment("愛", 8_000L, 9_000L),
                WordByWordSegment("說", 10_000L, 11_000L)
            ))), emptyText = "empty"
        )
        for (position in listOf(3_000L, 7_999L, 8_000L, 12_001L)) {
            renderer.updatePlayback(position, false)
            renderer.tick()
            assertEquals("爱说", view.text.toString())
            assertTrue("position=$position", view.text.highlightSpans().isEmpty())
        }
        renderer.updatePlayback(8_500L, false)
        renderer.tick()
        val span = view.text.highlightSpans().single()
        assertEquals(0, (view.text as Spanned).getSpanStart(span))
        assertEquals(1, (view.text as Spanned).getSpanEnd(span))
    }

    @Test
    fun scriptMapping_usesWholePhrasesAndValidUnicodeBoundaries() {
        ChineseText.installTableForTest(
            mapOf('著' to '着', '愛' to '爱'),
            phrases = mapOf("著作" to "著作", "愛愛" to "爱爱", "甲乙" to "丙")
        )
        val cases = listOf(
            Triple("著作爱", listOf("著", "作", "愛"), true),
            Triple("爱，爱😀", listOf("愛", "，", "愛", "😀"), true),
            Triple("丙爱", listOf("甲", "乙", "愛"), false),
            Triple("爱说", listOf("愛"), false),
            Triple("爱😀", listOf("愛", "\uD83D", "\uDE00"), false),
            Triple("爱爱", listOf("愛", "愛"), true)
        )
        for ((display, tokens, reliable) in cases) {
            val view = TextView(context)
            val renderer = renderer(textView = view, wordByWordEnabled = true)
            renderer.updatePlayback(1_500L, false)
            renderer.parseAndShow(
                plainLrc = "[00:01.00]$display",
                wordByWordLines = listOf(WordByWordLine(1_000L, 5_000L,
                    tokens.joinToString(""), tokens.mapIndexed { index, token ->
                        WordByWordSegment(token, 1_000L + index * 1_000L, 2_000L + index * 1_000L)
                    })), emptyText = "empty"
            )
            assertEquals(display, view.text.toString())
            assertEquals(display, reliable, view.text.highlightSpans().isNotEmpty())
        }
    }

    @Test
    fun updatePlayback_ignoresSmallStaleBacktrackWhilePlaying() {
        var now = 10_000L
        val renderer = renderer(uptimeMillisProvider = { now })

        renderer.updatePlayback(positionMs = 1_000L, isPlaying = true)
        now += 700L
        assertEquals(1_700L, renderer.getEstimatedPositionMs())

        renderer.updatePlayback(positionMs = 1_200L, isPlaying = true)
        assertEquals(1_700L, renderer.getEstimatedPositionMs())

        now += 100L
        assertEquals(1_800L, renderer.getEstimatedPositionMs())
    }

    @Test
    fun updatePlayback_acceptsLargeSeekAndPausedPosition() {
        var now = 10_000L
        val renderer = renderer(uptimeMillisProvider = { now })

        renderer.updatePlayback(positionMs = 10_000L, isPlaying = true)
        now += 3_000L
        assertEquals(13_000L, renderer.getEstimatedPositionMs())

        renderer.updatePlayback(positionMs = 8_000L, isPlaying = true)
        assertEquals(8_000L, renderer.getEstimatedPositionMs())

        now += 700L
        renderer.updatePlayback(positionMs = 8_200L, isPlaying = false)
        assertEquals(8_200L, renderer.getEstimatedPositionMs())
    }

    @Test
    fun setLyricsOffset_reportsChangesAndNeverReturnsANegativePosition() {
        val renderer = renderer()
        renderer.updatePlayback(positionMs = 1_000L, isPlaying = false)

        assertTrue(renderer.setLyricsOffset(500L))
        assertEquals(1_500L, renderer.getEstimatedPositionMs())
        assertFalse(renderer.setLyricsOffset(500L))

        assertTrue(renderer.setLyricsOffset(-2_000L))
        assertEquals(0L, renderer.getEstimatedPositionMs())
    }

    @Test
    fun parseAndTick_renderConfiguredPlainContentAndNeighborLines() {
        val textView = TextView(context)
        val renderer = renderer(
            textView = textView,
            contentMode = LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION,
            lineMode = LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT
        )
        renderer.parseAndShow(
            plainLrc = "[00:01.00]first\n[00:02.00]second\n[00:03.00]third",
            translatedLrc = "[00:01.00]第一句\n[00:02.00]第二句\n[00:03.00]第三句",
            emptyText = "empty"
        )

        renderer.updatePlayback(positionMs = 2_100L, isPlaying = false)
        renderer.tick()

        assertEquals("first\n第一句\nsecond\n第二句\nthird\n第三句", textView.text.toString())
    }

    @Test
    fun translationOnly_doesNotLeakWordByWordOriginalText() {
        val textView = TextView(context)
        val renderer = renderer(
            textView = textView,
            contentMode = LyricsContentDisplayMode.TRANSLATION_ONLY,
            wordByWordEnabled = true
        )
        renderer.updatePlayback(positionMs = 1_500L, isPlaying = false)

        renderer.parseAndShow(
            plainLrc = "[00:01.00]Hello",
            translatedLrc = "[00:01.00]你好",
            wordByWordLines = listOf(timedLine("Hello")),
            emptyText = "empty"
        )

        assertTrue(renderer.isWordByWordActive())
        assertEquals("你好", textView.text.toString())
        assertTrue(textView.text.highlightSpans().isEmpty())
    }

    @Test
    fun matchingWordByWordLine_highlightsOnlyCurrentOriginalAndKeepsTranslation() {
        val textView = TextView(context).apply { setTextColor(Color.WHITE) }
        val renderer = renderer(
            textView = textView,
            contentMode = LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION,
            wordByWordEnabled = true,
            highlightColor = Color.MAGENTA
        )
        renderer.updatePlayback(positionMs = 1_500L, isPlaying = false)

        renderer.parseAndShow(
            plainLrc = "[00:01.00]Hello",
            translatedLrc = "[00:01.00]你好",
            wordByWordLines = listOf(timedLine("Hello")),
            emptyText = "empty"
        )

        assertEquals("Hello\n你好", textView.text.toString())
        val text = textView.text as Spanned
        val completedSpan = text.highlightSpans()
            .first { it.foregroundColor == Color.MAGENTA }
        assertEquals(0, text.getSpanStart(completedSpan))
        assertEquals(2, text.getSpanEnd(completedSpan))
        assertTrue(text.highlightSpans().all { text.getSpanEnd(it) <= "Hello".length })
    }

    @Test
    fun translatedDuplicatePair_highlightsTheSimplifiedLine() {
        ChineseText.installTableForTest(mapOf('蘇' to '苏', '說' to '说'))
        val textView = TextView(context).apply { setTextColor(Color.WHITE) }
        val renderer = renderer(
            textView = textView,
            contentMode = LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION,
            wordByWordEnabled = true,
            highlightColor = Color.MAGENTA
        )
        renderer.updatePlayback(positionMs = 1_500L, isPlaying = false)

        val translated = "苏三说"
        val line = WordByWordLine(
            startMs = 1_000L,
            endMs = 2_000L,
            text = "蘇三說",
            segments = listOf(WordByWordSegment("蘇三說", 1_000L, 2_000L)),
            translatedText = translated,
            translatedSegments = translated.mapIndexed { index, character ->
                val start = 1_000L + 1_000L * index / translated.length
                WordByWordSegment(character.toString(), start, start + 333L)
            }
        )

        renderer.parseAndShow(
            plainLrc = "[00:01.00]蘇三說",
            translatedLrc = "[00:01.00]苏三说",
            wordByWordLines = listOf(line),
            emptyText = "empty"
        )

        assertEquals("苏三说", textView.text.toString())
        val text = textView.text as Spanned
        val spans = text.highlightSpans()
        assertTrue(spans.isNotEmpty())
        assertTrue(spans.all { text.getSpanEnd(it) <= translated.length })
    }

    @Test
    fun twoLineMode_highlightsTheTranslationLine() {
        ChineseText.installTableForTest(mapOf('蘇' to '苏', '說' to '说'))
        val textView = TextView(context).apply { setTextColor(Color.WHITE) }
        val renderer = renderer(
            textView = textView,
            contentMode = LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION,
            wordByWordEnabled = true,
            highlightColor = Color.MAGENTA,
            scriptPreference = LyricsScriptPreference.NONE
        )
        renderer.updatePlayback(positionMs = 1_500L, isPlaying = false)

        val translated = "苏三说"
        val line = WordByWordLine(
            startMs = 1_000L,
            endMs = 2_000L,
            text = "蘇三說",
            segments = listOf(WordByWordSegment("蘇三說", 1_000L, 2_000L)),
            translatedText = translated,
            translatedSegments = translated.mapIndexed { index, character ->
                val start = 1_000L + 1_000L * index / translated.length
                WordByWordSegment(character.toString(), start, start + 333L)
            }
        )

        renderer.parseAndShow(
            plainLrc = "[00:01.00]蘇三說",
            translatedLrc = "[00:01.00]苏三说",
            wordByWordLines = listOf(line),
            emptyText = "empty"
        )

        assertEquals("蘇三說\n苏三说", textView.text.toString())
        val text = textView.text as Spanned
        val lineStart = "蘇三說".length + 1
        assertTrue(text.highlightSpans().any { text.getSpanStart(it) >= lineStart })
    }

    @Test
    fun aLineWithoutTranslation_staysTraditional() {
        ChineseText.installTableForTest(mapOf('蘇' to '苏', '說' to '说'))
        val textView = TextView(context).apply { setTextColor(Color.WHITE) }
        val renderer = renderer(
            textView = textView,
            contentMode = LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION,
            wordByWordEnabled = true,
            highlightColor = Color.MAGENTA
        )
        renderer.updatePlayback(positionMs = 1_500L, isPlaying = false)

        val line = WordByWordLine(
            startMs = 1_000L,
            endMs = 2_000L,
            text = "蘇三說",
            segments = listOf(
                WordByWordSegment("蘇", 1_000L, 1_333L),
                WordByWordSegment("三", 1_333L, 1_666L),
                WordByWordSegment("說", 1_666L, 2_000L)
            )
        )

        renderer.parseAndShow(
            plainLrc = "[00:01.00]蘇三說",
            translatedLrc = null,
            wordByWordLines = listOf(line),
            emptyText = "empty"
        )

        assertEquals("蘇三說", textView.text.toString())
    }

    @Test
    fun translatedDuplicatePairWithoutTranslatedTokens_mapsTheScriptConversion() {
        ChineseText.installTableForTest(mapOf('蘇' to '苏', '說' to '说'))
        val textView = TextView(context)
        val renderer = renderer(
            textView = textView,
            contentMode = LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION,
            wordByWordEnabled = true,
            highlightColor = Color.MAGENTA
        )
        renderer.updatePlayback(positionMs = 1_500L, isPlaying = false)

        renderer.parseAndShow(
            plainLrc = "[00:01.00]蘇三說",
            translatedLrc = "[00:01.00]苏三说",
            wordByWordLines = listOf(timedLine("蘇三說")),
            emptyText = "empty"
        )

        assertEquals("苏三说", textView.text.toString())
        // No stored per-token translation: the script conversion is one-to-one, so the original
        // timings are sliced onto the displayed translation instead of dropping the highlight.
        assertTrue((textView.text as Spanned).highlightSpans().isNotEmpty())
    }

    @Test
    fun mismatchedWordByWordLine_fallsBackToUnhighlightedPlainLyrics() {
        val textView = TextView(context)
        val renderer = renderer(textView = textView, wordByWordEnabled = true)
        renderer.updatePlayback(positionMs = 1_500L, isPlaying = false)

        renderer.parseAndShow(
            plainLrc = "[00:01.00]Hello",
            wordByWordLines = listOf(timedLine("Unrelated words")),
            emptyText = "empty"
        )

        assertEquals("Hello", textView.text.toString())
        assertTrue(textView.text.highlightSpans().isEmpty())
    }

    @Test
    fun wordByWordOnlyPayload_stillRendersWhenPlainLrcHasNoTimedLines() {
        val textView = TextView(context)
        val renderer = renderer(textView = textView, wordByWordEnabled = true)
        renderer.updatePlayback(positionMs = 1_500L, isPlaying = false)

        renderer.parseAndShow(
            plainLrc = "not a timed lyric",
            wordByWordLines = listOf(timedLine("Standalone")),
            emptyText = "empty"
        )

        assertEquals("Standalone", textView.text.toString())
        assertTrue(textView.text.highlightSpans().isNotEmpty())
    }

    @Test
    fun parseAndShow_reportsWhetherParsedContentContainsUsableLyrics() {
        val renderer = renderer(textView = TextView(context), wordByWordEnabled = true)

        assertEquals(
            ParsedLyricsAvailability.EMPTY,
            renderer.parseAndShow("[ar:Artist]", emptyText = "empty")
        )
        assertEquals(
            ParsedLyricsAvailability.AVAILABLE,
            renderer.parseAndShow(
                plainLrc = "",
                translatedLrc = "[00:01.00]translation",
                emptyText = "empty"
            )
        )
        assertEquals(
            ParsedLyricsAvailability.AVAILABLE,
            renderer.parseAndShow(
                plainLrc = "not timed",
                wordByWordLines = listOf(timedLine("Standalone")),
                emptyText = "empty"
            )
        )
    }

    @Test
    fun statusMessage_survivesTextViewReplacementUntilClear() {
        var currentView: TextView? = TextView(context)
        val renderer = FloatingLyricsRenderer(textViewProvider = { currentView })

        renderer.show("lyrics unavailable")
        val restoredMessageView = TextView(context)
        currentView = restoredMessageView
        renderer.refresh()

        assertEquals("lyrics unavailable", restoredMessageView.text.toString())

        renderer.clear()
        val clearedView = TextView(context)
        currentView = clearedView
        renderer.refresh()

        assertEquals("", clearedView.text.toString())
    }

    @Test
    fun refreshAndClear_resetTextWithoutKeepingPreviousTimingState() {
        val textView = TextView(context)
        val renderer = renderer(textView = textView)
        renderer.updatePlayback(positionMs = 1_500L, isPlaying = false)
        renderer.parseAndShow("[00:01.00]line", emptyText = "empty")
        assertEquals("line", textView.text.toString())

        renderer.show("manual")
        renderer.refresh()
        assertEquals("manual", textView.text.toString())

        renderer.clear()
        assertEquals(0L, renderer.getEstimatedPositionMs())
        assertFalse(renderer.isWordByWordActive())
        assertEquals(1f, textView.alpha)
        assertEquals(0f, textView.translationY)
        assertEquals(1f, textView.scaleX)
        assertEquals(1f, textView.scaleY)
    }

    @Test
    fun animatedLineSwitch_keepsFloatingBubbleTransformAtRest() {
        val textView = RecordingAnimationView(context)
        val renderer = renderer(
            textView = textView,
            switchAnimationMode = LyricsSwitchAnimationMode.SLIDE_UP
        )
        renderer.updatePlayback(positionMs = 1_100L, isPlaying = false)
        renderer.parseAndShow(
            plainLrc = "[00:01.00]first\n[00:02.00]second",
            emptyText = "empty"
        )

        renderer.updatePlayback(positionMs = 2_100L, isPlaying = false)
        renderer.tick()

        assertEquals("second", textView.text.toString())
        assertEquals(1, textView.animationDurations.size)
        assertTrue(textView.animationDurations.single() > 0L)
        assertEquals(0f, textView.contentAlpha)
        assertTrue("SLIDE_UP must animate content from below", textView.contentTranslationY > 0f)
        assertEquals(1f, textView.contentScaleX)
        assertEquals(1f, textView.contentScaleY)
        assertEquals(1f, textView.alpha)
        assertEquals(0f, textView.translationY)
        assertEquals(1f, textView.scaleX)
        assertEquals(1f, textView.scaleY)
    }

    @Test fun displaysLinkedAttributionAndClearsExpiredPayload() {
        val view = TextView(context)
        val renderer = renderer(textView = view)
        val access = LyricsAccess(0, Long.MAX_VALUE, "key",
            LyricsAttribution("Spicy Lyrics", listOf(LyricsCredit("Maker", "https://example.com/maker"))))
        renderer.setLyricsAccess(access)
        renderer.parseAndShow("[00:01.00]Test", emptyText = "empty")
        assertEquals("Test\nSpicy Lyrics · Maker", view.text.toString())
        assertEquals("https://example.com/maker", (view.text as Spanned).getSpans(0, view.text.length, URLSpan::class.java).single().url)
        renderer.setLyricsAccess(access.copy(expiresAtMs = 1))
        renderer.tick()
        assertFalse(view.text.contains("Test"))
        assertFalse(view.text.contains("Maker"))
        renderer.refresh()
        assertFalse(view.text.contains("Test"))
    }
    @Test fun backgroundDoesNotReplaceLeadOrBecomeTranslation() {
        val view = TextView(context)
        val renderer = renderer(textView = view, wordByWordEnabled = true)
        renderer.updatePlayback(1500, false)
        renderer.parseAndShow("[00:01.00]Lead", wordByWordLines = listOf(
            WordByWordLine(1000, 3000, "Lead", listOf(WordByWordSegment("Lead", 1000, 3000))),
            WordByWordLine(1200, 2000, "Background", listOf(WordByWordSegment("Background", 1200, 2000)), true)
        ), emptyText = "empty")
        assertTrue(view.text.contains("Lead"))
        assertTrue(view.text.contains("Background"))
        renderer.updatePlayback(2200, false)
        renderer.tick()
        assertTrue(view.text.contains("Lead"))
        assertFalse(view.text.contains("Background"))
    }

    private class RecordingAnimationView(context: Context) : TextView(context), LyricsTextAnimationTarget {
        var contentAlpha = 1f
        var contentTranslationY = 0f
        var contentScaleX = 1f
        var contentScaleY = 1f
        val animationDurations = mutableListOf<Long>()

        override fun cancelLyricsTextAnimation() = Unit
        override fun setLyricsTextAnimationState(alpha: Float, translationY: Float, scaleX: Float, scaleY: Float) {
            contentAlpha = alpha
            contentTranslationY = translationY
            contentScaleX = scaleX
            contentScaleY = scaleY
        }
        override fun animateLyricsTextToRest(durationMs: Long, interpolator: TimeInterpolator) {
            animationDurations += durationMs
        }
    }

    private fun renderer(
        textView: TextView? = null,
        contentMode: LyricsContentDisplayMode = LyricsContentDisplayMode.ORIGINAL_ONLY,
        lineMode: LyricsLineDisplayMode = LyricsLineDisplayMode.CURRENT_ONLY,
        switchAnimationMode: LyricsSwitchAnimationMode = LyricsSwitchAnimationMode.NONE,
        wordByWordEnabled: Boolean = false,
        highlightColor: Int = Color.MAGENTA,
        scriptPreference: LyricsScriptPreference = LyricsScriptPreference.SIMPLIFIED,
        uptimeMillisProvider: () -> Long = { 10_000L }
    ): FloatingLyricsRenderer {
        return FloatingLyricsRenderer(
            textViewProvider = { textView },
            contentModeProvider = { contentMode },
            lineModeProvider = { lineMode },
            switchAnimationModeProvider = { switchAnimationMode },
            wordByWordLyricsEnabledProvider = { wordByWordEnabled },
            wordByWordHighlightColorProvider = { highlightColor },
            scriptPreferenceProvider = { scriptPreference },
            noTranslationTextProvider = { "no translation" },
            uptimeMillisProvider = uptimeMillisProvider
        )
    }

    private fun timedLine(text: String): WordByWordLine {
        return WordByWordLine(
            startMs = 1_000L,
            endMs = 2_000L,
            text = text,
            segments = listOf(WordByWordSegment(text, 1_000L, 2_000L))
        )
    }

    private fun CharSequence.highlightSpans(): Array<ForegroundColorSpan> {
        return (this as? Spanned)
            ?.getSpans(0, length, ForegroundColorSpan::class.java)
            ?: emptyArray()
    }
}
