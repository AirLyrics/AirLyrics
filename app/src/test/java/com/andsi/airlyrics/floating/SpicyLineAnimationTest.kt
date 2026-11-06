package com.andsi.airlyrics.floating

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Spanned
import android.text.TextUtils
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.app.host.applyPreviewLyricsLine
import com.andsi.airlyrics.app.host.updatePreviewLyricsText
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode
import com.andsi.airlyrics.core.model.WordByWordAnimationMode
import com.andsi.airlyrics.lyrics.LyricsAccess
import com.andsi.airlyrics.core.model.LyricsAttribution
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpicyLineAnimationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun view() = FloatingLyricsTextView(context).apply {
        layoutParams = android.view.ViewGroup.LayoutParams(280, 200)
        textSize = 24f
        setTextColor(Color.WHITE)
    }

    private fun measure(view: View, width: Int = 280, height: Int = 200) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun pixels(view: View): IntArray {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return IntArray(view.width * view.height).also {
            bitmap.getPixels(it, 0, view.width, 0, 0, view.width, view.height)
            bitmap.recycle()
        }
    }

    private fun spans(view: FloatingLyricsTextView): List<SpicyLineSpan> {
        val text = view.text as? Spanned ?: return emptyList()
        return text.getSpans(0, text.length, SpicyLineSpan::class.java).toList()
    }

    @Test fun framesUsePlaybackTimeWithoutReplacingLayout_andPauseFreezes() {
        val view = view().apply { setBackgroundColor(Color.BLUE) }
        var clock = 1L
        val renderer = FloatingLyricsRenderer({ view },
            switchAnimationModeProvider = { LyricsSwitchAnimationMode.SPICY },
            uptimeMillisProvider = { clock })
        renderer.updatePlayback(0, true)
        renderer.parseAndShow("[00:00.00]Hello world\n[00:04.00]Next", emptyText = "")
        measure(view)
        val text = view.text
        val layout = view.layout
        val initial = pixels(view)
        clock = 1501
        val middle = pixels(view)
        assertFalse(initial.contentEquals(middle))
        renderer.tick()
        assertSame(text, view.text)
        assertSame(layout, view.layout)
        assertEquals(Color.BLUE, middle.last())
        assertEquals(1f, view.alpha)
        assertEquals(1f, view.scaleX)
        assertEquals(0f, view.translationY)

        renderer.updatePlayback(1500, false)
        val paused = pixels(view)
        clock += 1000
        assertArrayEquals(paused, pixels(view))
        renderer.updatePlayback(0, false)
        assertFalse(paused.contentEquals(pixels(view)))
    }

    @Test fun wrappedSentenceWipesDownwardAcrossBothRows() {
        val view = view()
        var position = 1000L
        val text = android.text.SpannableString("AAAA\nAAAA").apply {
            setSpan(SpicyLineSpan(SpicyLineTiming(0, 0, 0, 4000), Color.WHITE),
                0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        view.text = text
        view.updateSpicyPlayback({ position }, false)
        measure(view)
        val first = pixels(view)
        val offset = view.extendedPaddingTop
        fun brightness(image: IntArray, row: Int): Long {
            val top = offset + view.layout.getLineTop(row)
            val bottom = offset + view.layout.getLineBottom(row)
            return (top until bottom).sumOf { y ->
                (0 until view.width).sumOf { x ->
                    val color = image[y * view.width + x]
                    (Color.red(color) * Color.alpha(color) / 255).toLong()
                }
            }
        }
        assertTrue(brightness(first, 0) > brightness(first, 1) * 1.3)
        position = 3000
        val later = pixels(view)
        assertTrue(brightness(later, 1) > brightness(first, 1) * 1.4)
        assertTrue(spans(view).all { it.hidden })
    }

    @Test fun identicalConsecutiveTextGetsNewTiming_andBlankTimestampClearsInk() {
        val view = view()
        val renderer = FloatingLyricsRenderer({ view },
            switchAnimationModeProvider = { LyricsSwitchAnimationMode.SPICY },
            durationMsProvider = { 10_000 })
        renderer.updatePlayback(1000, false)
        renderer.parseAndShow("[00:01.00]Again\n[00:02.00]Again\n[00:03.00]\n[00:04.00]Last", emptyText = "")
        val first = spans(view).single().timing
        renderer.updatePlayback(2100, false)
        renderer.tick()
        assertEquals("Again", view.text.toString())
        assertNotEquals(first, spans(view).single().timing)
        assertEquals(2000L, spans(view).single().timing.startMs)
        assertEquals(3000L, spans(view).single().timing.endMs)
        renderer.updatePlayback(3100, false)
        renderer.tick()
        assertEquals("", view.text.toString())
        renderer.updatePlayback(4100, false)
        renderer.tick()
        assertEquals(10_000L, spans(view).single().timing.endMs)
    }

    @Test fun neighborsTranslationsAndAttributionHaveSeparateRanges() {
        val view = view()
        val renderer = FloatingLyricsRenderer({ view },
            contentModeProvider = { LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION },
            lineModeProvider = { LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT },
            switchAnimationModeProvider = { LyricsSwitchAnimationMode.SPICY })
        renderer.updatePlayback(2500, false)
        renderer.setLyricsAccess(LyricsAccess(0, Long.MAX_VALUE, "key", LyricsAttribution("Spicy Lyrics", emptyList())))
        renderer.parseAndShow("[00:01.00]First\n[00:02.00]Current\n[00:04.00]Next",
            translatedLrc = "[00:02.00]当前歌词", emptyText = "")
        val spans = spans(view)
        assertEquals(4, spans.size)
        val current = spans.filter { it.timing.lineIndex == 1 }
        assertEquals(2, current.size)
        assertTrue(current.all { it.timing.startMs == 2000L && it.timing.endMs == 4000L })
        val text = view.text as Spanned
        val credit = text.indexOf("Spicy Lyrics")
        assertTrue(credit >= 0)
        assertTrue(text.getSpans(credit, text.length, SpicyLineSpan::class.java).isEmpty())
        measure(view)
        pixels(view)
        renderer.show("Waiting")
        assertTrue(spans(view).isEmpty())
        assertEquals("Waiting", view.text.toString())
    }

    @Test fun wordTimingWins_butEnabledWordsWithoutTimingStillUseLineAnimation() {
        val view = view()
        val renderer = FloatingLyricsRenderer({ view },
            switchAnimationModeProvider = { LyricsSwitchAnimationMode.SPICY },
            wordByWordLyricsEnabledProvider = { true },
            wordByWordAnimationModeProvider = { WordByWordAnimationMode.SPICY })
        renderer.updatePlayback(500, false)
        renderer.parseAndShow("[00:00.00]Hello", wordByWordLines = listOf(
            WordByWordLine(0, 2000, "Hello", listOf(WordByWordSegment("Hello", 0, 2000)))
        ), emptyText = "")
        assertTrue(spans(view).isEmpty())
        val text = view.text as Spanned
        assertTrue(text.getSpans(0, text.length, SpicyLyricsSpan::class.java).isNotEmpty())

        renderer.parseAndShow("[00:00.00]Hello", wordByWordLines = listOf(
            WordByWordLine(0, 2000, "Hello", emptyList())
        ), emptyText = "")
        assertFalse(renderer.isWordByWordActive())
        assertEquals(1, spans(view).size)
    }

    @Test fun boundarySchedulingUsesEstimatedPositionAndOffset() {
        var clock = 1L
        val view = view()
        val renderer = FloatingLyricsRenderer({ view }, uptimeMillisProvider = { clock })
        renderer.updatePlayback(1700, true)
        renderer.parseAndShow("[00:01.00]First\n[00:02.00]Next", emptyText = "")
        assertEquals(300L, renderer.nextSyncDelayMs(300))
        clock += 250
        assertEquals(50L, renderer.nextSyncDelayMs(300))
        renderer.setLyricsOffset(25)
        assertEquals(25L, renderer.nextSyncDelayMs(300))
        renderer.updatePlayback(1950, false)
        assertEquals(300L, renderer.nextSyncDelayMs(300))
    }

    @Test fun ellipsisJoiningScriptsAndEmojiStayReadable() {
        for ((text, ellipsis) in listOf("مرحبا بالعالم" to false, "Hello 世界 👨‍👩‍👧‍👦" to false,
            "A long lyric that must truncate" to true)) {
            val view = view()
            val renderer = FloatingLyricsRenderer({ view },
                switchAnimationModeProvider = { LyricsSwitchAnimationMode.SPICY })
            renderer.updatePlayback(1000, false)
            renderer.parseAndShow("[00:00.00]$text\n[00:04.00]Next", emptyText = "")
            if (ellipsis) { view.maxLines = 1; view.ellipsize = TextUtils.TruncateAt.END }
            measure(view, 150)
            assertTrue(pixels(view).any { Color.alpha(it) > 0 })
            assertEquals(text, view.text.toString())
        }
    }

    @Test fun disabledSystemMotionKeepsTimedFillWithoutScale() {
        val oldScale = ValueAnimator.getDurationScale()
        try {
            ReflectionHelpers.callStaticMethod<Unit>(ValueAnimator::class.java, "setDurationScale",
                ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, 0f))
            val view = view()
            var position = 1000L
            view.text = android.text.SpannableString("Hello").apply {
                setSpan(SpicyLineSpan(SpicyLineTiming(0, 0, 0, 4000), Color.WHITE), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            view.updateSpicyPlayback({ position }, false)
            measure(view)
            val before = pixels(view)
            position = 3000
            assertFalse(before.contentEquals(pixels(view)))
        } finally {
            ReflectionHelpers.callStaticMethod<Unit>(ValueAnimator::class.java, "setDurationScale",
                ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, oldScale))
        }
    }

    @Test fun mixedDirectionTextKeepsNativeGlyphPositions() {
        val oldScale = ValueAnimator.getDurationScale()
        try {
            ReflectionHelpers.callStaticMethod<Unit>(ValueAnimator::class.java, "setDurationScale",
                ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, 0f))
            for (text in listOf("مرحبا world 123 عالم", "Hello مرحبا 123 world", "世界 Hello")) {
                val animated = view()
                val renderer = FloatingLyricsRenderer({ animated },
                    switchAnimationModeProvider = { LyricsSwitchAnimationMode.SPICY },
                    wordByWordHighlightColorProvider = { Color.WHITE },
                    glowStyleProvider = { com.andsi.airlyrics.core.model.LyricsGlowStyle(enabled = false) })
                renderer.updatePlayback(3999, false)
                renderer.parseAndShow("[00:00.00]$text\n[00:04.00]Next", emptyText = "")
                measure(animated)
                val native = androidx.appcompat.widget.AppCompatTextView(context).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(280, 200)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, animated.textSize)
                    setTextColor(Color.WHITE)
                    setPadding(animated.compoundPaddingLeft, animated.compoundPaddingTop,
                        animated.compoundPaddingRight, animated.compoundPaddingBottom)
                    this.text = text
                }
                measure(native)
                val actual = pixels(animated)
                val expected = pixels(native)
                val nativeInk = expected.count { Color.alpha(it) > 20 }
                val differences = actual.indices.count { kotlin.math.abs(Color.alpha(actual[it]) - Color.alpha(expected[it])) > 30 }
                assertTrue("Glyph placement must match TextView for $text ($differences / $nativeInk)", differences < nativeInk * .1)
            }
        } finally {
            ReflectionHelpers.callStaticMethod<Unit>(ValueAnimator::class.java, "setDurationScale",
                ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, oldScale))
        }
    }

    @Test fun previewAttachesTheSameTimedParagraphs_andSettingPersists() {
        val text = android.text.SpannableStringBuilder("Original\nTranslation")
        applyPreviewLyricsLine(text, 0, 8, 0, 0, Color.CYAN)
        applyPreviewLyricsLine(text, 9, text.length, 0, 1, Color.CYAN)
        val view = view()
        updatePreviewLyricsText(view, text)
        assertEquals(2, spans(view).size)
        assertTrue(spans(view).all { it.timing.startMs == 0L && it.timing.endMs == 4000L })
        measure(view)
        assertTrue(pixels(view).any { Color.alpha(it) > 0 })
        LyricsSettingsStore.setSwitchAnimationMode(context, LyricsSwitchAnimationMode.SPICY)
        assertEquals(LyricsSwitchAnimationMode.SPICY, LyricsSettingsStore.getSwitchAnimationMode(context))
        LyricsSettingsStore.setSwitchAnimationMode(context, LyricsSwitchAnimationMode.default)
    }
}
