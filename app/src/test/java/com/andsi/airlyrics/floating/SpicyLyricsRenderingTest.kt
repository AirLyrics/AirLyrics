package com.andsi.airlyrics.floating

import android.content.Context
import android.text.Spanned
import android.text.style.URLSpan
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.LyricsAttribution
import com.andsi.airlyrics.core.model.LyricsCredit
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode
import com.andsi.airlyrics.lyrics.LyricsAccess
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SpicyLyricsRenderingTest {
    @Test fun displaysLinkedAttributionAndClearsExpiredPayload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = TextView(context)
        val renderer = FloatingLyricsRenderer(textViewProvider = { view }, switchAnimationModeProvider = { LyricsSwitchAnimationMode.NONE })
        val access = LyricsAccess(System.currentTimeMillis() - 1000, Long.MAX_VALUE, "key",
            LyricsAttribution("Spicy Lyrics", listOf(LyricsCredit("Maker", "https://example.com/maker"))))
        renderer.setLyricsAccess(access)
        renderer.parseAndShow("[00:01.00]Test", emptyText = "empty")
        assertEquals("Test\nSpicy Lyrics · Maker", view.text.toString())
        assertEquals("https://example.com/maker", (view.text as Spanned).getSpans(0, view.text.length, URLSpan::class.java).single().url)
        renderer.setLyricsAccess(access.copy(expiresAtMs = 1))
        renderer.tick()
        assertFalse(view.text.contains("Test"))
        assertFalse(view.text.contains("Maker"))
        renderer.refresh()
        assertFalse(view.text.contains("Test"))
    }
    @Test fun backgroundDoesNotReplaceLeadOrBecomeTranslation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = TextView(context)
        val renderer = FloatingLyricsRenderer(textViewProvider = { view }, wordByWordLyricsEnabledProvider = { true })
        renderer.updatePlayback(1500, false)
        renderer.parseAndShow("[00:01.00]Lead", wordByWordLines = listOf(
            WordByWordLine(1000, 3000, "Lead", listOf(WordByWordSegment("Lead", 1000, 3000))),
            WordByWordLine(1200, 2000, "Background", listOf(WordByWordSegment("Background", 1200, 2000)), true)
        ), emptyText = "empty")
        assertTrue(view.text.contains("Lead"))
        assertTrue(view.text.contains("Background"))
        renderer.updatePlayback(2200, false)
        renderer.tick()
        assertTrue(view.text.contains("Lead"))
        assertFalse(view.text.contains("Background"))
    }
}
