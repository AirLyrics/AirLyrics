package com.andsi.airlyrics.ui.pages.floating

import android.app.Activity
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import com.andsi.airlyrics.app.host.createClassicPreviewProgress
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
        val highlighter = ClassicPreviewHighlighter(
            text, start, Color.RED, Color.WHITE, createClassicPreviewProgress(line)
        )

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
    fun `classic preview reports the exact color position to its halo clock`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val lyric = TextView(activity).apply { text = "Current lyric" }
        var reported = -1L
        val loop = FloatingPreviewClassicLoop(
            view = lyric, sampleLine = { "Current lyric" }, highlightColor = { Color.RED },
            progressForLine = ::createClassicPreviewProgress,
            onPositionChanged = { reported = it },
            isVisible = { true }
        )
        loop.sync()
        assertEquals(0L, reported)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(800, TimeUnit.MILLISECONDS)
        val spans = lyric.text as Spanned
        val red = spans.getSpans(0, spans.length, ForegroundColorSpan::class.java)
            .single { it.foregroundColor == Color.RED }
        val expected = createClassicPreviewProgress("Current lyric")(reported)
        assertEquals(expected.completedEnd, spans.getSpanEnd(red))
        assertTrue(reported > 0L)
        lyric.text = ""
        loop.sync()
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
            progressForLine = ::createClassicPreviewProgress,
            isVisible = { true }
        )

        loop.sync()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(800, TimeUnit.MILLISECONDS)
        assertTrue(loop.hasTarget)
        val highlighted = lyric.text as Spanned
        assertTrue(highlighted.getSpans(0, highlighted.length, ForegroundColorSpan::class.java).isNotEmpty())

        line = null
        loop.sync()
        assertFalse(loop.hasTarget)
        assertTrue(highlighted.getSpans(0, highlighted.length, ForegroundColorSpan::class.java).isEmpty())
    }

    @Test
    fun `classic preview stops offscreen and returns with no accumulated highlight`() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val lyric = TextView(controller.get()).apply { text = "Current lyric" }
            var visible = true
            var position = -1L
            val loop = FloatingPreviewClassicLoop(
                lyric, { "Current lyric" }, { Color.RED }, ::createClassicPreviewProgress,
                onPositionChanged = { position = it }, isVisible = { visible }
            )
            loop.sync()
            val looper = shadowOf(android.os.Looper.getMainLooper())
            looper.idleFor(800, TimeUnit.MILLISECONDS)
            assertTrue(position > 0L)

            visible = false
            loop.sync()
            val stopped = position
            looper.idleFor(30, TimeUnit.SECONDS)
            assertEquals(stopped, position)
            val text = lyric.text as Spanned
            assertTrue(text.getSpans(0, text.length, ForegroundColorSpan::class.java).isEmpty())

            visible = true
            loop.sync()
            assertEquals(0L, position)
            assertTrue(text.getSpans(0, text.length, ForegroundColorSpan::class.java).isEmpty())
            lyric.text = ""
            loop.sync()
        }
    }

    @Test
    fun `viewport visibility excludes partially clipped and folded previews`() {
        Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            // Control window visibility explicitly; legacy Robolectric leaves AttachInfo GONE.
            var windowVisible = true
            val lyric = object : TextView(activity) {
                override fun getWindowVisibility() = if (windowVisible) View.VISIBLE else View.GONE
            }.apply { text = "Preview" }
            val content = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(lyric, android.widget.LinearLayout.LayoutParams(300, 80))
                addView(View(activity), android.widget.LinearLayout.LayoutParams(300, 1000))
            }
            val scroll = android.widget.ScrollView(activity).apply { addView(content) }
            activity.setContentView(scroll)
            val decor = activity.window.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 300, 400)
            assertTrue(lyric.isPreviewFullyVisible())
            scroll.scrollTo(0, 20)
            assertFalse(lyric.isPreviewFullyVisible())
            scroll.scrollTo(0, 200)
            assertFalse(lyric.isPreviewFullyVisible())
            scroll.scrollTo(0, 0)
            assertTrue(lyric.isPreviewFullyVisible())
            lyric.visibility = View.GONE
            assertFalse(lyric.isPreviewFullyVisible())
            lyric.visibility = View.VISIBLE
            windowVisible = false
            assertFalse(lyric.isPreviewFullyVisible())
        }
    }

    @Test
    fun `preview loop runs only while lyric is visible and animation is enabled`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        controller.use {
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
        }
    }
}
