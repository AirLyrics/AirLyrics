package com.andsi.airlyrics.ui.pages.floating

import android.app.Activity
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class FloatingPreviewAnimationTest {
    @Test
    fun `classic preview advances through every character and leaves other lines untouched`() {
        val line = "歌词😀演示"
        val start = "上一行\n".length
        val text = SpannableStringBuilder("上一行\n${line}\n译文")
        val highlighter = ClassicPreviewHighlighter(text, line, start, Color.RED, Color.WHITE)

        highlighter.renderAt(0L)
        assertTrue(text.getSpans(0, text.length, ForegroundColorSpan::class.java).isEmpty())

        highlighter.renderAt(FloatingPageTokens.PREVIEW_CLASSIC_FILL_MS / 2)
        val halfwaySpans = text.getSpans(0, text.length, ForegroundColorSpan::class.java)
        assertEquals(2, halfwaySpans.size)
        val completed = halfwaySpans.single { it.foregroundColor == Color.RED }
        assertEquals(start, text.getSpanStart(completed))
        assertEquals(start + 2, text.getSpanEnd(completed))
        val active = halfwaySpans.single { it !== completed }
        assertEquals(start + 2, text.getSpanStart(active))
        assertEquals(start + 4, text.getSpanEnd(active))
        assertFalse(active.foregroundColor == Color.WHITE || active.foregroundColor == Color.RED)

        highlighter.renderAt(FloatingPageTokens.PREVIEW_CLASSIC_FILL_MS)
        val full = text.getSpans(0, text.length, ForegroundColorSpan::class.java).single()
        assertEquals(start, text.getSpanStart(full))
        assertEquals(start + line.length, text.getSpanEnd(full))

        highlighter.renderAt(0L)
        assertTrue(text.getSpans(0, text.length, ForegroundColorSpan::class.java).isEmpty())
        assertEquals("上一行\n${line}\n译文", text.toString())
    }

    @Test
    fun `classic preview clears its highlight when the mode changes`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val lyric = TextView(activity).apply { text = "上一行\n当前歌词\n译文" }
        var line: String? = "当前歌词"
        val loop = FloatingPreviewClassicLoop(
            view = lyric,
            sampleLine = { line },
            highlightColor = { Color.RED },
            isVisible = { false }
        )

        loop.sync()
        assertTrue(loop.hasTarget)
        val highlighted = lyric.text as Spanned
        assertTrue(highlighted.getSpans(0, highlighted.length, ForegroundColorSpan::class.java).isNotEmpty())

        line = null
        loop.sync()
        assertFalse(loop.hasTarget)
        assertTrue(highlighted.getSpans(0, highlighted.length, ForegroundColorSpan::class.java).isEmpty())
    }

    @Test
    fun `preview loop runs only while lyric is visible and animation is enabled`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        try {
            val lyric = TextView(activity).apply { text = "Preview lyric" }
            activity.setContentView(lyric)
            val decor = activity.window.decorView
            val width = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
            val height = View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY)
            decor.measure(width, height)
            decor.layout(0, 0, 400, 800)

            var enabled = true
            var plays = 0
            val loop = FloatingPreviewSwitchLoop(
                view = lyric,
                shouldAnimate = { enabled },
                play = { plays++ },
                isVisible = { lyric.isAttachedToWindow && lyric.isShown }
            )
            val mainLooper = shadowOf(android.os.Looper.getMainLooper())

            loop.sync()
            mainLooper.idleFor(FloatingPageTokens.PREVIEW_LOOP_START_DELAY_MS + 20, TimeUnit.MILLISECONDS)
            assertEquals(1, plays)
            mainLooper.idleFor(FloatingPageTokens.PREVIEW_LOOP_INTERVAL_MS, TimeUnit.MILLISECONDS)
            assertEquals(2, plays)

            lyric.visibility = View.GONE
            loop.sync()
            mainLooper.idleFor(FloatingPageTokens.PREVIEW_LOOP_INTERVAL_MS * 2, TimeUnit.MILLISECONDS)
            assertEquals(2, plays)

            lyric.visibility = View.VISIBLE
            loop.sync()
            mainLooper.idleFor(FloatingPageTokens.PREVIEW_LOOP_START_DELAY_MS + 20, TimeUnit.MILLISECONDS)
            assertEquals(3, plays)

            enabled = false
            loop.sync()
            mainLooper.idleFor(FloatingPageTokens.PREVIEW_LOOP_INTERVAL_MS, TimeUnit.MILLISECONDS)
            assertEquals(3, plays)
        } finally {
            controller.close()
        }
    }
}
