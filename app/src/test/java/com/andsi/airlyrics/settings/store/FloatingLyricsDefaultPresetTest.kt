package com.andsi.airlyrics.settings.store

import android.graphics.Color
import android.view.Gravity
import com.andsi.airlyrics.core.model.FloatingLyricsFontFamily
import com.andsi.airlyrics.core.model.FloatingLyricsFontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
/** Independent default preset contract; visual default changes are reviewed here. */
class FloatingLyricsDefaultPresetTest : SettingsStoreTestBase() {
    @Test
    fun returnsDefaultBubbleStyle() {
        val style = FloatingLyricsStyleStore.getStyle(context)

        assertEquals(FloatingLyricsStyleStore.DEFAULT_PRESET, style.presetName)
        assertEquals(18f, style.textSizeSp)
        assertEquals(Color.WHITE, style.textColor)
        assertEquals(Color.rgb(120, 220, 255), style.wordByWordHighlightColor)
        assertEquals(Color.BLACK, style.shadowColor)
        assertEquals(8f, style.shadowRadius)
        assertTrue(style.backgroundEnabled)
        assertEquals(Color.rgb(10, 14, 24), style.backgroundColor)
        assertEquals(170, style.backgroundAlpha)
        assertEquals(20, style.cornerRadiusDp)
        assertEquals(18, style.paddingHorizontalDp)
        assertEquals(10, style.paddingVerticalDp)
        assertEquals(85, style.maxWidthPercent)
        assertEquals(Gravity.CENTER, style.gravity)
        assertEquals(FloatingLyricsFontFamily.SYSTEM_DEFAULT, style.fontFamily)
        assertEquals(FloatingLyricsFontWeight.DEFAULT, style.fontWeight)
        assertEquals(100 to 300, FloatingLyricsStyleStore.getPosition(context))
        assertTrue(FloatingLyricsStyleStore.isPreviewExpanded(context))
        assertFalse(FloatingLyricsStyleStore.isAutoHideWhenPaused(context))
        assertFalse(FloatingLyricsStyleStore.isAutoHideWhenLyricsUnavailable(context))
    }

}
