package com.andsi.airlyrics.floating

import android.content.Context
import android.graphics.Color
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
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

    private fun renderer(
        textView: TextView? = null,
        contentMode: LyricsContentDisplayMode = LyricsContentDisplayMode.ORIGINAL_ONLY,
        lineMode: LyricsLineDisplayMode = LyricsLineDisplayMode.CURRENT_ONLY,
        wordByWordEnabled: Boolean = false,
        highlightColor: Int = Color.MAGENTA,
        uptimeMillisProvider: () -> Long = { 10_000L }
    ): FloatingLyricsRenderer {
        return FloatingLyricsRenderer(
            textViewProvider = { textView },
            contentModeProvider = { contentMode },
            lineModeProvider = { lineMode },
            switchAnimationModeProvider = { LyricsSwitchAnimationMode.NONE },
            wordByWordLyricsEnabledProvider = { wordByWordEnabled },
            wordByWordHighlightColorProvider = { highlightColor },
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
