package com.andsi.airlyrics.floating

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.SpannableString
import android.text.Spanned
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.FloatingLyricsFontFamily
import com.andsi.airlyrics.settings.store.FloatingLyricsFontStore
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-mdpi")
class FloatingLyricsFontRenderingTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun installLocalDownloadFixtures() {
        File(context.filesDir, "floating_fonts").deleteRecursively()
        for ((id, resource) in listOf(
            "swei_gothic_sc" to R.font.font_preview_swei_gothic_sc,
            "wenkai" to R.font.font_preview_wenkai
        )) {
            val temporary = File.createTempFile("render-font-", ".ttf", context.cacheDir)
            try {
                context.resources.openRawResource(resource).use { input ->
                    temporary.outputStream().use { input.copyTo(it) }
                }
                assertTrue(FloatingLyricsFontStore.installDownloadedFont(context, id, temporary))
            } finally {
                temporary.delete()
            }
        }
    }

    private enum class Rendering { PLAIN, SPICY_LINE, SPICY_WORD }

    private class Ink(val width: Int, val height: Int, val mask: BooleanArray) {
        fun matches(other: Ink): Boolean =
            width == other.width && height == other.height && mask.contentEquals(other.mask)
    }

    @Test
    fun switchingPresetsChangesCjkGlyphsInPlainAndSpicyLyrics() {
        for (sample in listOf("这是一行歌词预览", "這是一行歌詞預覽", "歌詞プレビュー")) {
            for (rendering in Rendering.entries) {
                val view = lyricView(sample, rendering)
                fun ink(family: FloatingLyricsFontFamily): Ink {
                    FloatingLyricsFontStore.applyTypeface(view, family, 400)
                    return render(view).let { bitmap ->
                        try {
                            croppedInk(bitmap)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }

                val system = ink(FloatingLyricsFontFamily.SYSTEM_DEFAULT)
                val rounded = ink(FloatingLyricsFontFamily.ROUNDED)
                val handwriting = ink(FloatingLyricsFontFamily.HANDWRITING)
                val label = "$rendering: $sample"

                assertFalse("$label: rounded must change CJK glyphs", system.matches(rounded))
                assertFalse("$label: handwriting must change CJK glyphs", system.matches(handwriting))
                assertFalse("$label: rounded and handwriting must differ", rounded.matches(handwriting))
                assertTrue("$label: switching back must restore system glyphs",
                    system.matches(ink(FloatingLyricsFontFamily.SYSTEM_DEFAULT)))
            }
        }
    }

    @Test
    fun captureAvailableCjkFontComparison() {
        val families = listOf(
            FloatingLyricsFontFamily.SYSTEM_DEFAULT to "System default",
            FloatingLyricsFontFamily.SERIF to "Serif",
            FloatingLyricsFontFamily.ROUNDED to "Swei Gothic SC",
            FloatingLyricsFontFamily.HANDWRITING to "LXGW WenKai"
        )
        val bitmap = Bitmap.createBitmap(760, families.size * 96, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY
                textSize = 20f
            }
            families.forEachIndexed { index, (family, label) ->
                val view = lyricView("这是一行歌词预览", Rendering.PLAIN).apply {
                    textSize = 28f
                    FloatingLyricsFontStore.applyTypeface(this, family, 400)
                }
                val row = render(view)
                try {
                    assertTrue("$label must render visible glyphs", croppedInk(row).width > 0)
                    canvas.drawText(label, 16f, index * 96f + 48f, labelPaint)
                    canvas.drawBitmap(row, 176f, index * 96f + 14f, null)
                } finally {
                    row.recycle()
                }
            }
            File("/tmp/airlyrics-swei-wenkai-cjk-font-comparison.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun lyricView(sample: String, rendering: Rendering) =
        FloatingLyricsTextView(context).apply {
            textSize = 40f
            setTextColor(Color.BLACK)
            includeFontPadding = false
            text = if (rendering == Rendering.PLAIN) sample else SpannableString(sample).apply {
                val span = when (rendering) {
                    Rendering.SPICY_LINE -> SpicyLineSpan(
                        SpicyLineTiming(0, 0, 0, 1000), Color.BLACK
                    )
                    Rendering.SPICY_WORD -> SpicyLyricsSpan(
                        SpicyTiming(0, length, 0, 1000, emphasized = false, motion = false),
                        Color.BLACK
                    )
                    Rendering.PLAIN -> error("Plain text does not need a drawing span")
                }
                setSpan(span, 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (rendering != Rendering.PLAIN) updateSpicyPlayback({ 5000L }, false)
        }

    private fun render(view: View): Bitmap {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(560, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
            view.draw(Canvas(it))
        }
    }

    private fun croppedInk(bitmap: Bitmap): Ink {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (Color.alpha(pixels[y * bitmap.width + x]) >= 32) {
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        }
        assertTrue("The renderer must draw visible glyphs", right >= left && bottom >= top)
        val width = right - left + 1
        val height = bottom - top + 1
        // Ignore baseline/ascent differences: Latin-only font aliases must not pass
        // merely because they move identical fallback glyphs up or down.
        val mask = BooleanArray(width * height) { index ->
            Color.alpha(pixels[(top + index / width) * bitmap.width + left + index % width]) >= 32
        }
        return Ink(width, height, mask)
    }
}
