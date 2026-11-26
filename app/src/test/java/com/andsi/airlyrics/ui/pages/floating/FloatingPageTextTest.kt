package com.andsi.airlyrics.ui.pages.floating

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FloatingPageTextTest {
    @Test
    fun `formatted preview keeps original and translation on separate lines`() {
        val rendered = formattedPreviewLyrics(
            mode = LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION,
            lines = listOf(
                FloatingPreviewLyricLine("Previous original", "Previous translation", isCurrent = false),
                FloatingPreviewLyricLine("Current original", "Current translation", isCurrent = true)
            ),
            textColor = Color.WHITE
        )

        assertEquals(
            "Previous original\nPrevious translation\nCurrent original\nCurrent translation",
            rendered.toString()
        )

        val spanned = rendered as Spanned
        val relativeSizeSpans = spanned.getSpans(0, spanned.length, RelativeSizeSpan::class.java)
        assertEquals(3, relativeSizeSpans.size)

        val currentStart = rendered.indexOf("Current original")
        val currentBoldSpan = spanned.getSpans(
            currentStart,
            currentStart + "Current original".length,
            StyleSpan::class.java
        )
        assertTrue(currentBoldSpan.any { it.style == Typeface.BOLD })
    }

    @Test
    fun `single content modes do not leave empty translation rows`() {
        val line = FloatingPreviewLyricLine("Original", "Translation", isCurrent = true)

        assertEquals(
            "Original",
            formattedPreviewLyrics(LyricsContentDisplayMode.ORIGINAL_ONLY, listOf(line), Color.WHITE).toString()
        )
        assertEquals(
            "Translation",
            formattedPreviewLyrics(LyricsContentDisplayMode.TRANSLATION_ONLY, listOf(line), Color.WHITE).toString()
        )
    }

    @Test
    fun `preview uses the actual minimum and preserves the legacy size curve`() {
        val legacyPreviews = mapOf(
            LyricsLineDisplayMode.CURRENT_ONLY to Triple(16f, 17.3333f, 30f),
            LyricsLineDisplayMode.PREVIOUS_AND_CURRENT to Triple(14f, 14.9524f, 24f),
            LyricsLineDisplayMode.CURRENT_AND_NEXT to Triple(14f, 14.9524f, 24f),
            LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT to Triple(12f, 12.7619f, 20f),
        )

        legacyPreviews.forEach { (mode, sizes) ->
            val (at14, at18, at56) = sizes
            assertEquals(4f, previewTextSizeSp(4f, mode), 0.001f)
            assertEquals(at14, previewTextSizeSp(14f, mode), 0.001f)
            assertEquals(at18, previewTextSizeSp(18f, mode), 0.001f)
            assertEquals(at56, previewTextSizeSp(56f, mode), 0.001f)
            assertTrue(previewTextSizeSp(10f, mode) in 4f..at14)
            assertEquals(at14, previewTextSizeSp(13.99f, mode), 0.02f)
        }
    }

    @Test
    fun `opacity conversion uses display percentages`() {
        assertEquals(0, alphaToOpacityPercent(-1))
        assertEquals(50, alphaToOpacityPercent(128))
        assertEquals(100, alphaToOpacityPercent(300))
        assertEquals(0, opacityPercentToAlpha(-1))
        assertEquals(128, opacityPercentToAlpha(50))
        assertEquals(255, opacityPercentToAlpha(101))
    }
}
