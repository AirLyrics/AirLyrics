package com.andsi.airlyrics.floating

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FloatingLyricsTextViewTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun transparentLyricContent_keepsBubbleBackgroundVisible() {
        val backgroundColor = Color.rgb(20, 40, 60)
        val view = FloatingLyricsTextView(context).apply {
            text = "Lyrics"
            background = ColorDrawable(backgroundColor)
            setLyricsTextAnimationState(
                alpha = 0f,
                translationY = 12f,
                scaleX = 0.9f,
                scaleY = 0.9f
            )
            measure(exactly(160), exactly(64))
            layout(0, 0, measuredWidth, measuredHeight)
        }
        val bitmap = Bitmap.createBitmap(160, 64, Bitmap.Config.ARGB_8888)

        view.draw(Canvas(bitmap))

        assertEquals(backgroundColor, bitmap.getPixel(8, 8))
        assertEquals(1f, view.alpha)
        assertEquals(0f, view.translationY)
        assertEquals(1f, view.scaleX)
        assertEquals(1f, view.scaleY)
    }

    private fun exactly(size: Int): Int {
        return View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    }
}
