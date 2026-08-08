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
    fun `preview font mapping is bounded and reacts to the real size`() {
        val expectedBounds = mapOf(
            LyricsLineDisplayMode.CURRENT_ONLY to (16f to 30f),
            LyricsLineDisplayMode.PREVIOUS_AND_CURRENT to (14f to 24f),
            LyricsLineDisplayMode.CURRENT_AND_NEXT to (14f to 24f),
            LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT to (12f to 20f)
        )

        expectedBounds.forEach { (mode, bounds) ->
            assertEquals(bounds.first, previewTextSizeSp(14f, mode), 0.001f)
            assertEquals(bounds.second, previewTextSizeSp(56f, mode), 0.001f)
            assertTrue(previewTextSizeSp(42f, mode) > previewTextSizeSp(28f, mode))
        }
    }
}
