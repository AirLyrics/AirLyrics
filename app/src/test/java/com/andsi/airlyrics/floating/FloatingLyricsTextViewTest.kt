package com.andsi.airlyrics.floating

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import android.view.animation.LinearInterpolator
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloatingLyricsTextViewTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric otherwise advances vsync automatically until the animation ends.
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    @After
    fun tearDown() {
        ShadowChoreographer.setPaused(false)
    }

    @Test
    fun transparentContentHidesAllGlyphsButKeepsBackground_thenAnimationRestoresGlyphs() {
        val backgroundColor = Color.rgb(20, 40, 60)
        val view = FloatingLyricsTextView(context).apply {
            text = "Lyrics"
            textSize = 24f
            setTextColor(Color.WHITE)
            background = ColorDrawable(backgroundColor)
            measure(exactly(160), exactly(64))
            layout(0, 0, measuredWidth, measuredHeight)
        }
        fun pixels(): IntArray {
            val bitmap = Bitmap.createBitmap(160, 64, Bitmap.Config.ARGB_8888)
            return try {
                view.draw(Canvas(bitmap))
                IntArray(160 * 64).also { bitmap.getPixels(it, 0, 160, 0, 0, 160, 64) }
            } finally {
                bitmap.recycle()
            }
        }
        val visible = pixels()
        assertTrue("Positive control must actually draw glyphs", visible.any { it != backgroundColor })

        view.setLyricsTextAnimationState(0f, 12f, 0.9f, 0.9f)
        assertTrue("Transparent glyphs must leave only the bubble", pixels().all { it == backgroundColor })
        assertEquals(1f, view.alpha)
        assertEquals(0f, view.translationY)
        assertEquals(1f, view.scaleX)
        assertEquals(1f, view.scaleY)

        view.animateLyricsTextToRest(200, LinearInterpolator())
        repeat(6) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        val middle = pixels()
        assertTrue("Animation must reveal content before it finishes", middle.any { it != backgroundColor })
        assertTrue("Mid-animation content must differ from rest", !middle.contentEquals(visible))
        assertEquals(backgroundColor, middle[0])
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
        org.junit.Assert.assertArrayEquals("Animation must restore the original glyph rendering", visible, pixels())
    }

    private fun exactly(size: Int): Int {
        return View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    }
}
