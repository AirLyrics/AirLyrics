package com.andsi.airlyrics.app.host

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.LyricsGlowStyle
import com.andsi.airlyrics.floating.FloatingLyricsTextView
import com.andsi.airlyrics.floating.SpicyLineSpan
import com.andsi.airlyrics.floating.SpicyLyricsSpan
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloatingPreviewRenderingTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun tuningGlowKeepsPreviewWrappingAndHeightFixed() {
        val view = preview(SpannableStringBuilder("A preview lyric with enough words to wrap").apply {
            applyPreviewLyricsHighlight(this, toString(), 0, Color.CYAN)
        })
        fun geometry(): List<Int> {
            measure(view)
            return listOf(view.measuredHeight, view.compoundPaddingLeft, view.compoundPaddingTop, view.lineCount)
        }
        val initial = geometry()
        for (spread in listOf(50, 100, LyricsGlowStyle.MAX_SPREAD_PERCENT)) {
            for (enabled in listOf(false, true)) {
                view.updateLyricsGlow(LyricsGlowStyle(enabled, 200, spread))
                assertEquals(initial, geometry())
            }
        }
    }

    @Test
    fun scrollingOutAndReturningRestartsWordAndLineEffectsLikeFolding() {
        for (lineMode in listOf(false, true)) {
            val text = SpannableStringBuilder("Preview lyric")
            if (lineMode) applyPreviewLyricsLine(text, 0, text.length, 0, 0, Color.CYAN)
            else applyPreviewLyricsHighlight(text, text.toString(), 0, Color.CYAN)
            val view = preview(text)
            view.syncPreviewVisibility(true)
            val first = pixels(view)
            ShadowSystemClock.advanceBy(Duration.ofMillis(800))
            assertFalse("Positive control: playback must advance", first.contentEquals(pixels(view)))

            view.syncPreviewVisibility(false)
            val spans = view.text as Spanned
            assertTrue(spans.getSpans(0, spans.length, SpicyLyricsSpan::class.java).none { it.hidden })
            assertTrue(spans.getSpans(0, spans.length, SpicyLineSpan::class.java).none { it.hidden })
            val hidden = pixels(view)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
            assertArrayEquals("Hidden previews must stay static", hidden, pixels(view))

            view.syncPreviewVisibility(true)
            assertArrayEquals("Returning must start at the first frame", first, pixels(view))
        }
    }

    @Test
    fun glowAndTransformedInkCannotPaintOutsideTheView() {
        val view = preview(SpannableStringBuilder("Glow").apply {
            applyPreviewLyricsHighlight(this, toString(), 0, Color.CYAN)
        })
        view.syncPreviewVisibility(true)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1_000))
        view.setLyricsTextAnimationState(1f, 15f, 1.4f, 1.4f)
        val width = view.width + 120
        val height = view.height + 120
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply { translate(60f, 60f); view.draw(this) }
            var inkInside = false
            for (y in 0 until height) for (x in 0 until width) {
                val alpha = Color.alpha(bitmap.getPixel(x, y))
                if (x in 60 until 60 + view.width && y in 60 until 60 + view.height) {
                    inkInside = inkInside || alpha > 0
                } else assertEquals("Ink escaped view bounds at ($x, $y)", 0, alpha)
            }
            assertTrue("Positive control: glyphs must be rendered", inkInside)
        } finally {
            bitmap.recycle()
        }
    }

    private fun preview(text: CharSequence) = FloatingLyricsTextView(context, preview = true).apply {
        textSize = 24f
        setTextColor(Color.WHITE)
        updatePreviewLyricsText(this, text)
        measure(this)
    }

    private fun measure(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun pixels(view: View): IntArray {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return try {
            view.draw(Canvas(bitmap))
            IntArray(view.width * view.height).also {
                bitmap.getPixels(it, 0, view.width, 0, 0, view.width, view.height)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
