package com.andsi.airlyrics.floating

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.LyricsGlowStyle
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode
import com.andsi.airlyrics.core.model.WordByWordAnimationMode
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsGlowTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val purple = Color.rgb(92, 38, 130)

    private fun bitmap(draw: (Canvas) -> Unit): IntArray {
        val bitmap = Bitmap.createBitmap(280, 180, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap))
        return IntArray(280 * 180).also {
            bitmap.getPixels(it, 0, 280, 0, 0, 280, 180)
            bitmap.recycle()
        }
    }

    private fun halo(style: LyricsGlowStyle, color: Int = purple, shadow: Boolean = false): IntArray {
        val view = FloatingLyricsTextView(context)
        if (shadow) view.setShadowLayer(5f, 4f, 4f, Color.RED)
        val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 32f; this.color = color }
        val halo = LyricsHalo(view, paint, color)
        return bitmap { halo.draw(it, "Halo", 0, 4, 0, 4, 70f, 90f, false, paint.measureText("Halo"), 1f, style) }
    }

    /** Outside the entire glyph bounding box: fill/scale differences cannot make this pass. */
    private fun outerEnergy(image: IntArray): Long {
        val bounds = Rect()
        TextPaint().apply { textSize = 32f }.getTextBounds("Halo", 0, 4, bounds)
        bounds.offset(70, 90)
        bounds.inset(-2, -2)
        return image.indices.sumOf { index ->
            if (bounds.contains(index % 280, index / 280)) 0L else Color.alpha(image[index]).toLong()
        }
    }

    @Test fun darkAccentProducesVisibleOuterGlow_andOffProducesNone() {
        assertTrue(outerEnergy(halo(LyricsGlowStyle())) > 1000)
        assertEquals(0L, outerEnergy(halo(LyricsGlowStyle(enabled = false))))
        assertEquals(0L, outerEnergy(halo(LyricsGlowStyle(strengthPercent = 0))))
        assertEquals(0L, outerEnergy(halo(LyricsGlowStyle(), Color.TRANSPARENT)))
    }

    @Test fun intensitySpreadAndTextOpacityControlOuterHalo() {
        assertTrue(outerEnergy(halo(LyricsGlowStyle(strengthPercent = 150))) >
            outerEnergy(halo(LyricsGlowStyle(strengthPercent = 50))) * 1.5)
        assertTrue(outerEnergy(halo(LyricsGlowStyle(spreadPercent = 200))) >
            outerEnergy(halo(LyricsGlowStyle(spreadPercent = 50))) * 1.5)
        assertTrue(outerEnergy(halo(LyricsGlowStyle(), Color.argb(64, 92, 38, 130))) <
            outerEnergy(halo(LyricsGlowStyle())))
    }

    @Test fun disablingGlowDoesNotRemoveUserShadow() {
        val shadow = halo(LyricsGlowStyle(enabled = false), shadow = true)
        assertTrue(outerEnergy(shadow) > 0)
        assertTrue(shadow.any { Color.alpha(it) > 5 && Color.red(it) > Color.green(it) * 2 })
    }

    private fun view() = FloatingLyricsTextView(context).apply {
        layoutParams = android.view.ViewGroup.LayoutParams(280, 180)
        textSize = 24f
        setTextColor(Color.GREEN)
    }

    private fun measure(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 280, 180)
    }

    @Test fun everyLineAnimationReceivesHaloWithoutForcingSpicyFillOrTransform() {
        for (mode in LyricsSwitchAnimationMode.entries) {
            val view = view()
            val renderer = FloatingLyricsRenderer({ view }, switchAnimationModeProvider = { mode },
                wordByWordHighlightColorProvider = { purple })
            renderer.updatePlayback(2000, false)
            renderer.parseAndShow("[00:00.00]Halo\n[00:04.00]Next", emptyText = "")
            val text = view.text as Spanned
            val span = text.getSpans(0, text.length, SpicyLineSpan::class.java).single()
            assertEquals(mode == LyricsSwitchAnimationMode.SPICY, span.animated)
            measure(view)
            assertTrue(bitmap(view::draw).any { Color.alpha(it) > 0 })
            assertEquals("Halo", view.text.toString())
            assertEquals(1f, view.scaleX)
            assertEquals(0f, view.translationY)
        }
    }

    @Test fun classicWordModeRetainsNativeColorsAndAddsOnlyHalo() {
        val view = view()
        val renderer = FloatingLyricsRenderer({ view },
            wordByWordLyricsEnabledProvider = { true },
            wordByWordAnimationModeProvider = { WordByWordAnimationMode.CLASSIC },
            wordByWordHighlightColorProvider = { purple })
        renderer.updatePlayback(500, false)
        renderer.parseAndShow("[00:00.00]Hello", wordByWordLines = listOf(
            WordByWordLine(0, 2000, "Hello", listOf(WordByWordSegment("Hello", 0, 2000)))
        ), emptyText = "")
        val text = view.text as Spanned
        val colors = text.getSpans(0, text.length, ForegroundColorSpan::class.java)
        assertTrue(colors.isNotEmpty())
        val spans = text.getSpans(0, text.length, SpicyLyricsSpan::class.java)
        assertTrue(spans.isNotEmpty())
        assertTrue(spans.all { it.haloOnly })
        assertEquals(2000L, spans.single().timing.endMs) // No Spicy early finish or letter emphasis.
        measure(view)
        bitmap(view::draw)
        assertTrue(spans.all { !it.hidden })
    }

    @Test fun disablingGlowKeepsSpicySweep_andPlainModeReturnsToNativeText() {
        for (mode in listOf(LyricsSwitchAnimationMode.SPICY, LyricsSwitchAnimationMode.FADE)) {
            val view = view()
            var settings = LyricsGlowStyle()
            val renderer = FloatingLyricsRenderer({ view }, switchAnimationModeProvider = { mode },
                glowStyleProvider = { settings })
            renderer.updatePlayback(2000, false)
            renderer.parseAndShow("[00:00.00]Hello\n[00:04.00]Next", emptyText = "")
            measure(view)
            bitmap(view::draw)
            settings = settings.copy(enabled = false)
            renderer.refresh()
            measure(view)
            val text = view.text as? Spanned
            val spans = text?.getSpans(0, text.length, SpicyLineSpan::class.java).orEmpty()
            assertEquals(if (mode == LyricsSwitchAnimationMode.SPICY) 1 else 0, spans.size)
            assertEquals("Hello", view.text.toString())
        }
    }

    @Test fun reducedMotionKeepsWeakStationaryGlow() {
        val oldScale = ValueAnimator.getDurationScale()
        try {
            ReflectionHelpers.callStaticMethod<Unit>(ValueAnimator::class.java, "setDurationScale",
                ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, 0f))
            val view = view()
            val renderer = FloatingLyricsRenderer({ view },
                switchAnimationModeProvider = { LyricsSwitchAnimationMode.SPICY })
            renderer.updatePlayback(2000, false)
            renderer.parseAndShow("[00:00.00]Hello\n[00:04.00]Next", emptyText = "")
            measure(view)
            val first = bitmap(view::draw)
            assertArrayEquals(first, bitmap(view::draw))
            assertEquals(1f, view.scaleX)
        } finally {
            ReflectionHelpers.callStaticMethod<Unit>(ValueAnimator::class.java, "setDurationScale",
                ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, oldScale))
        }
    }

    @Test fun settingsPreserveLegacyColorClampValuesAndResetWithPreset() {
        val prefs = context.getSharedPreferences("floating_lyrics_style", Context.MODE_PRIVATE)
        prefs.edit().clear().putInt("karaoke_highlight_color", purple).commit()
        val original = FloatingLyricsStyleStore.getStyle(context)
        assertEquals(purple, original.wordByWordHighlightColor)
        assertEquals(LyricsGlowStyle(), original.glow)
        FloatingLyricsStyleStore.setStyle(context, original.copy(glow = LyricsGlowStyle(false, 160, 180)))
        assertEquals(LyricsGlowStyle(false, 160, 180), FloatingLyricsStyleStore.getStyle(context).glow)
        prefs.edit().putInt("glow_strength_percent", 999).putInt("glow_spread_percent", -10).commit()
        assertEquals(LyricsGlowStyle(false, 200, 50), FloatingLyricsStyleStore.getGlowStyle(context))
        FloatingLyricsStyleStore.applyPreset(context, FloatingLyricsStyleStore.PRESET_BUBBLE)
        assertEquals(LyricsGlowStyle(), FloatingLyricsStyleStore.getStyle(context).glow)
    }
}
