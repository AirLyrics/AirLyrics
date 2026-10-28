package com.andsi.airlyrics.floating

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.WordByWordAnimationMode
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import com.andsi.airlyrics.settings.store.LyricsSettingsStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpicyLyricsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun line(text: String, duration: Long = 1500) = WordByWordLine(0, duration, text,
        listOf(WordByWordSegment(text, 0, duration)))

    @Test fun graphemesAreNeverSplitAndEmphasisUsesOneSecondThreshold() {
        val text = "e\u0301👨‍👩‍👧‍👦好"
        val units = spicyTimings(line(text), text)
        assertEquals(listOf("e\u0301", "👨‍👩‍👧‍👦", "好"), units.map { text.substring(it.start, it.end) })
        assertEquals(1, spicyTimings(line("hello", 999), "hello").size)
        assertEquals(5, spicyTimings(line("hello", 1000), "hello").size)
        assertEquals(1250L, units.last().endMs) // Spicy normal-mode emphasis finishes 250ms early.
    }

    @Test fun repeatedWordsMapForwardAndInvalidMappingFallsBack() {
        val line = WordByWordLine(0, 1000, "go go", listOf(
            WordByWordSegment("go", 0, 400), WordByWordSegment("go", 500, 1000)))
        assertEquals(listOf(0, 3), spicyTimings(line, line.text).map { it.start })
        assertTrue(spicyTimings(line, "go").isEmpty())
        assertTrue(spicyTimings(line("bad", 0), "bad").isEmpty())
        assertFalse(spicyTimings(line("مرحبا"), "مرحبا").single().motion)
    }

    @Test fun progressAndSpringAreStableAcrossFramesAndSeeking() {
        assertEquals(0f, spicyProgress(99, 100, 200))
        assertEquals(.5f, spicyProgress(150, 100, 200))
        assertEquals(1f, spicyProgress(201, 100, 200))
        val a = SpicySpring(.88, .64, .95f)
        val b = SpicySpring(.88, .64, .95f)
        repeat(60) { a.step(1.05f, 1.0 / 60) }
        repeat(120) { b.step(1.05f, 1.0 / 120) }
        assertEquals(a.value, b.value, .00001f)
        b.reset(.95f)
        assertEquals(.95f, b.value)
    }

    @Test fun settingRoundTripsAndUnknownValuesUseClassic() {
        assertEquals(WordByWordAnimationMode.CLASSIC, WordByWordAnimationMode.fromKey("future"))
        LyricsSettingsStore.setWordByWordAnimationMode(context, WordByWordAnimationMode.SPICY)
        assertEquals(WordByWordAnimationMode.SPICY, LyricsSettingsStore.getSettings(context).wordByWordAnimationMode)
        LyricsSettingsStore.setWordByWordAnimationMode(context, WordByWordAnimationMode.CLASSIC)
    }

    @Test fun framesChangeInkWithoutChangingTextOrLayout_andPauseIsStable() {
        val view = FloatingLyricsTextView(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(400, 150)
            textSize = 24f; setTextColor(Color.WHITE)
        }
        var clock = 1L
        val renderer = FloatingLyricsRenderer(textViewProvider = { view },
            wordByWordLyricsEnabledProvider = { true },
            wordByWordAnimationModeProvider = { WordByWordAnimationMode.SPICY }, uptimeMillisProvider = { clock })
        renderer.updatePlayback(0, true)
        renderer.parseAndShow("", wordByWordLines = listOf(line("Hello world")), emptyText = "")
        view.measure(exact(200), exact(150)); view.layout(0, 0, 200, 150)
        val originalText = view.text
        val originalLayout = view.layout
        val start = pixels(view)
        clock = 751
        val mid = pixels(view)
        assertFalse(start.contentEquals(mid))
        renderer.tick()
        assertSame(originalText, view.text)
        assertSame(originalLayout, view.layout)
        renderer.updatePlayback(750, false)
        val paused = pixels(view)
        clock = 1001
        assertArrayEquals(paused, pixels(view))
        renderer.updatePlayback(0, false)
        assertFalse(paused.contentEquals(pixels(view)))
    }

    @Test fun wrappedTextAndEllipsisStillDrawVisibleGlyphs() {
        for (ellipsis in listOf(false, true)) {
            val view = FloatingLyricsTextView(context).apply {
                textSize = 24f; setTextColor(Color.WHITE)
                if (ellipsis) { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
                val words = "A long lyric with emoji 🎵"
                text = SpannableString(words).apply {
                    for (timing in spicyTimings(line(words), words)) setSpan(SpicyLyricsSpan(timing, Color.CYAN), timing.start, timing.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                updateSpicyPlayback({ 700L }, false)
                measure(exact(130), exact(200)); layout(0, 0, 130, 200)
            }
            assertTrue(pixels(view).any { Color.alpha(it) > 0 })
        }
    }

    @Test fun completedInkMatchesNativeTextIncludingJoiningScripts() {
        for (opacity in listOf(255, 128)) for (words in listOf("Hello world", "星光 e\u0301 🎵", "مرحبا بالعالم")) {
            val animated = FloatingLyricsTextView(context).apply {
                textSize = 24f; setTextColor(Color.argb(opacity, 255, 255, 255))
                text = SpannableString(words).apply {
                    for (timing in spicyTimings(line(words), words)) setSpan(SpicyLyricsSpan(timing, Color.argb(opacity, 255, 255, 255)),
                        timing.start, timing.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                updateSpicyPlayback({ 2000L }, false)
                measure(exact(400), exact(100)); layout(0, 0, 400, 100)
            }
            val native = FloatingLyricsTextView(context).apply {
                textSize = 24f; setTextColor(Color.argb(opacity, 255, 255, 255)); text = words
                setPadding(animated.compoundPaddingLeft, animated.compoundPaddingTop,
                    animated.compoundPaddingRight, animated.compoundPaddingBottom)
                measure(exact(400), exact(100)); layout(0, 0, 400, 100)
            }
            val expected = pixels(native)
            val actual = pixels(animated)
            val changed = expected.indices.count { expected[it] != actual[it] }
            assertTrue("Native shaping must be preserved for $words (different pixels: $changed)", changed < 20)
        }
    }

    @Test fun switchingModesWhilePlayingPreservesProgressAndRestoresClassicInk() {
        val view = FloatingLyricsTextView(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(400, 150)
            textSize = 24f; setTextColor(Color.WHITE)
        }
        var mode = WordByWordAnimationMode.CLASSIC
        var clock = 10000L
        val renderer = FloatingLyricsRenderer(textViewProvider = { view },
            wordByWordLyricsEnabledProvider = { true },
            wordByWordAnimationModeProvider = { mode }, uptimeMillisProvider = { clock })
        renderer.updatePlayback(500, true)
        renderer.parseAndShow("", wordByWordLines = listOf(line("Hello world", 5000)), emptyText = "")
        fun layout() { view.measure(exact(400), exact(150)); view.layout(0, 0, 400, 150) }
        layout()
        val classic = pixels(view)
        mode = WordByWordAnimationMode.SPICY
        renderer.refresh()
        layout()
        val spicy = pixels(view)
        clock += 500
        assertFalse(spicy.contentEquals(pixels(view)))
        mode = WordByWordAnimationMode.CLASSIC
        renderer.refresh()
        layout()
        val restored = pixels(view)
        assertFalse(classic.contentEquals(restored))
        renderer.updatePlayback(500, false)
        renderer.tick()
        layout()
        assertArrayEquals(classic, pixels(view))
    }

    @Test fun pixelHeightLimitUsedByOverlayDoesNotDisableSpicyDrawing() {
        var position = 0L
        val view = FloatingLyricsTextView(context).apply {
            textSize = 24f
            setTextColor(Color.WHITE)
            includeFontPadding = false
            maxHeight = 500
            text = SpannableString("Hello world").apply {
                for (timing in spicyTimings(line(toString()), toString())) {
                    setSpan(SpicyLyricsSpan(timing, Color.CYAN), timing.start, timing.end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            updateSpicyPlayback({ position }, false)
            measure(exact(400), View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.AT_MOST))
            layout(0, 0, measuredWidth, measuredHeight)
        }
        assertEquals(-1, view.maxLines)
        val initial = pixels(view)
        position = 750
        assertFalse("Pixel height limits must not skip the animation runs", initial.contentEquals(pixels(view)))
        val spans = (view.text as Spanned).getSpans(0, view.text.length, SpicyLyricsSpan::class.java)
        assertTrue("Timed ink must be drawn by Spicy, not the static TextView pass", spans.all { it.hidden })
    }

    @Test fun sourceCurvesSeparateOrdinaryGlowFromLetterProximity() {
        assertEquals(.35f, SpicyLyricsCurves.glowOpacity(emphasized = false, strength = 1f))
        assertEquals(1f, SpicyLyricsCurves.glowOpacity(emphasized = true, strength = 1f))
        assertEquals(6f, SpicyLyricsCurves.glowRadius(56f, emphasized = false, strength = 1f))
        assertEquals(16f, SpicyLyricsCurves.glowRadius(56f, emphasized = true, strength = 1f))
        val word = SpicyTiming(0, 4, 0, 800, emphasized = false, motion = true)
        val pose = SpicyLyricsCurves.Pose()
        SpicyLyricsCurves.targets(word, 120, pose)
        assertEquals(1f, pose.glow, .00001f)
        SpicyLyricsCurves.targets(word, 800, pose)
        assertEquals(0f, pose.glow, .00001f)
        assertEquals(1f, pose.scale, .00001f)
        val letters = spicyTimings(line("abcd", 2250), "abcd")
        val active = SpicyLyricsCurves.Pose()
        val previous = SpicyLyricsCurves.Pose()
        val future = SpicyLyricsCurves.Pose()
        SpicyLyricsCurves.targets(letters[1], 700, active)
        SpicyLyricsCurves.targets(letters[0], 700, previous)
        SpicyLyricsCurves.targets(letters[2], 700, future)
        assertTrue(previous.glow > 0f && previous.glow < active.glow)
        assertEquals(0f, future.glow)
        assertEquals(.95f, future.scale)
        SpicyLyricsCurves.targets(letters[0], 2000, previous)
        assertEquals(0f, previous.glow)
        assertEquals(kotlin.math.sqrt(.5f), SpicyLyricsCurves.fillProgress(letters[0], 250), .00001f)
    }

    @Test fun activeGlowDoesNotReplaceUserShadow() {
        var position = 0L
        val view = FloatingLyricsTextView(context).apply {
            textSize = 36f
            setTextColor(Color.WHITE)
            setShadowLayer(5f, 5f, 5f, Color.RED)
            text = SpannableString("Hey").apply {
                val timing = spicyTimings(line("Hey", 800), "Hey").single()
                setSpan(SpicyLyricsSpan(timing, Color.CYAN), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            updateSpicyPlayback({ position }, false)
            measure(exact(300), exact(150)); layout(0, 0, 300, 150)
        }
        fun redPixels(image: IntArray) = image.count {
            Color.alpha(it) > 20 && Color.red(it) > Color.green(it) * 1.3f && Color.red(it) > Color.blue(it) * 1.3f
        }
        assertTrue("The user shadow must be visible before the glow starts", redPixels(pixels(view)) > 0)
        repeat(25) {
            position += 16
            android.os.SystemClock.sleep(16)
            pixels(view)
        }
        assertTrue("The red user shadow must survive the cyan dynamic glow", redPixels(pixels(view)) > 0)
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun pixels(view: View): IntArray {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return IntArray(view.width * view.height).also { bitmap.getPixels(it, 0, view.width, 0, 0, view.width, view.height); bitmap.recycle() }
    }
}
