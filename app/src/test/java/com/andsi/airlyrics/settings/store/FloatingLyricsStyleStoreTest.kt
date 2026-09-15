package com.andsi.airlyrics.settings.store

import android.content.Context
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
class FloatingLyricsStyleStoreTest : SettingsStoreTestBase() {
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
    }

    @Test
    fun appliesPresetAndClampsEditableValues() {
        FloatingLyricsStyleStore.applyPreset(context, FloatingLyricsStyleStore.PRESET_SUBTITLE)
        FloatingLyricsStyleStore.setTextSize(context, 100f)
        FloatingLyricsStyleStore.setShadowRadius(context, -4f)
        FloatingLyricsStyleStore.setPaddingHorizontal(context, 99)
        FloatingLyricsStyleStore.setPaddingVertical(context, -10)
        FloatingLyricsStyleStore.setMaxWidthPercent(context, 20)
        FloatingLyricsStyleStore.setBackgroundColor(context, Color.argb(12, 1, 2, 3))
        FloatingLyricsStyleStore.setBackgroundAlpha(context, 12)
        FloatingLyricsStyleStore.setBackgroundEnabled(context, true)
        FloatingLyricsStyleStore.savePosition(context, 321, 654)
        FloatingLyricsStyleStore.setPreviewExpanded(context, false)

        val style = FloatingLyricsStyleStore.getStyle(context)

        assertEquals(FloatingLyricsStyleStore.PRESET_SUBTITLE, style.presetName)
        assertEquals(56f, style.textSizeSp)
        assertEquals(0f, style.shadowRadius)
        assertEquals(36, style.paddingHorizontalDp)
        assertEquals(0, style.paddingVerticalDp)
        assertEquals(45, style.maxWidthPercent)
        assertTrue(style.backgroundEnabled)
        assertEquals(Color.rgb(1, 2, 3), style.backgroundColor)
        assertEquals(12, style.backgroundAlpha)
        assertEquals(321 to 654, FloatingLyricsStyleStore.getPosition(context))
        assertFalse(FloatingLyricsStyleStore.isPreviewExpanded(context))
    }

    @Test
    fun cleanLettersMatchesBubbleExceptForDisabledBackground() {
        FloatingLyricsStyleStore.applyPreset(context, FloatingLyricsStyleStore.PRESET_BUBBLE)
        val bubbleStyle = FloatingLyricsStyleStore.getStyle(context)

        FloatingLyricsStyleStore.applyPreset(context, FloatingLyricsStyleStore.PRESET_SUBTITLE)
        val cleanLettersStyle = FloatingLyricsStyleStore.getStyle(context)

        assertEquals(
            bubbleStyle.copy(
                presetName = FloatingLyricsStyleStore.PRESET_SUBTITLE,
                backgroundEnabled = false
            ),
            cleanLettersStyle
        )
    }

    @Test
    fun presetDefaultsIgnoreEditsAndFullStyleCanBeRestored() {
        FloatingLyricsStyleStore.applyPreset(context, FloatingLyricsStyleStore.PRESET_SUBTITLE)
        FloatingLyricsStyleStore.setTextSize(context, 44f)
        FloatingLyricsStyleStore.setBackgroundEnabled(context, true)
        FloatingLyricsStyleStore.setCornerRadius(context, 4)

        val editedStyle = FloatingLyricsStyleStore.getStyle(context)
        val defaults = FloatingLyricsStyleStore.getPresetDefaults(editedStyle.presetName)

        assertEquals(18f, defaults.textSizeSp)
        assertFalse(defaults.backgroundEnabled)
        assertEquals(20, defaults.cornerRadiusDp)

        FloatingLyricsStyleStore.setStyle(context, defaults)

        assertEquals(defaults, FloatingLyricsStyleStore.getStyle(context))
    }

    @Test
    fun fontSettingsRoundTripAndNormalizeWeight() {
        assertEquals(6, FloatingLyricsFontWeight.toLevel(555))
        assertEquals(600, FloatingLyricsFontWeight.fromLevel(6))

        FloatingLyricsStyleStore.setStyle(
            context,
            FloatingLyricsStyleStore.getStyle(context).copy(
                fontFamily = FloatingLyricsFontFamily.MONOSPACE,
                fontWeight = 555
            )
        )

        var style = FloatingLyricsStyleStore.getStyle(context)
        assertEquals(FloatingLyricsFontFamily.MONOSPACE, style.fontFamily)
        assertEquals(600, style.fontWeight)

        context.getSharedPreferences("floating_lyrics_style", Context.MODE_PRIVATE)
            .edit()
            .putString("font_family", "future_font")
            .putInt("font_weight", 2_000)
            .commit()

        style = FloatingLyricsStyleStore.getStyle(context)
        assertEquals(FloatingLyricsFontFamily.SYSTEM_DEFAULT, style.fontFamily)
        assertEquals(FloatingLyricsFontWeight.MAX, style.fontWeight)
    }

    @Test
    fun fontOpacitySurvivesColorAndPresetChanges() {
        FloatingLyricsStyleStore.setTextAlpha(context, 96)
        FloatingLyricsStyleStore.setTextColor(context, Color.argb(12, 1, 2, 3))

        var style = FloatingLyricsStyleStore.getStyle(context)
        assertEquals(Color.argb(96, 1, 2, 3), style.textColor)

        FloatingLyricsStyleStore.applyPreset(context, FloatingLyricsStyleStore.PRESET_SUBTITLE)

        style = FloatingLyricsStyleStore.getStyle(context)
        assertEquals(96, Color.alpha(style.textColor))
        assertEquals(
            Color.WHITE,
            Color.rgb(Color.red(style.textColor), Color.green(style.textColor), Color.blue(style.textColor))
        )

        FloatingLyricsStyleStore.setTextAlpha(context, 300)
        assertEquals(255, Color.alpha(FloatingLyricsStyleStore.getStyle(context).textColor))

        FloatingLyricsStyleStore.setTextAlpha(context, -1)
        assertEquals(0, Color.alpha(FloatingLyricsStyleStore.getStyle(context).textColor))
    }

    @Test
    fun backgroundControlsPersistIndependentlyAndClampAlpha() {
        FloatingLyricsStyleStore.applyPreset(context, FloatingLyricsStyleStore.PRESET_SUBTITLE)
        FloatingLyricsStyleStore.setBackgroundColor(context, Color.argb(127, 1, 2, 3))

        var style = FloatingLyricsStyleStore.getStyle(context)
        assertFalse(style.backgroundEnabled)
        assertEquals(Color.rgb(1, 2, 3), style.backgroundColor)
        assertEquals(170, style.backgroundAlpha)

        FloatingLyricsStyleStore.setBackgroundAlpha(context, 300)

        style = FloatingLyricsStyleStore.getStyle(context)
        assertFalse(style.backgroundEnabled)
        assertEquals(255, style.backgroundAlpha)

        FloatingLyricsStyleStore.setBackgroundEnabled(context, true)
        FloatingLyricsStyleStore.setBackgroundAlpha(context, -1)

        style = FloatingLyricsStyleStore.getStyle(context)
        assertTrue(style.backgroundEnabled)
        assertEquals(0, style.backgroundAlpha)
    }

    @Test
    fun wordByWordHighlightColor_usesCompatibilityPreferenceKey() {
        val preferences = context.getSharedPreferences("floating_lyrics_style", Context.MODE_PRIVATE)
        val writtenColor = Color.rgb(12, 34, 56)

        FloatingLyricsStyleStore.setWordByWordHighlightColor(context, writtenColor)

        assertTrue(preferences.contains("karaoke_highlight_color"))
        assertEquals(writtenColor, preferences.getInt("karaoke_highlight_color", Color.TRANSPARENT))

        val persistedColor = Color.rgb(65, 43, 21)
        preferences.edit()
            .clear()
            .putInt("karaoke_highlight_color", persistedColor)
            .commit()

        assertEquals(persistedColor, FloatingLyricsStyleStore.getStyle(context).wordByWordHighlightColor)
    }

    @Test
    fun clickThroughFallsBackToLockedUntilExplicitlySet() {
        assertFalse(FloatingLyricsStyleStore.isLocked(context))
        assertFalse(FloatingLyricsStyleStore.isClickThrough(context))
        assertTrue(FloatingLyricsStyleStore.isClickThroughFollowingLocked(context))

        FloatingLyricsStyleStore.setLocked(context, true)

        assertTrue(FloatingLyricsStyleStore.isClickThrough(context))
        assertTrue(FloatingLyricsStyleStore.isClickThroughFollowingLocked(context))

        FloatingLyricsStyleStore.setClickThrough(context, false)

        assertFalse(FloatingLyricsStyleStore.isClickThrough(context))
        assertFalse(FloatingLyricsStyleStore.isClickThroughFollowingLocked(context))
    }

    @Test
    fun autoHideWhenPausedDefaultsOffAndRoundTrips() {
        assertFalse(FloatingLyricsStyleStore.isAutoHideWhenPaused(context))

        FloatingLyricsStyleStore.setAutoHideWhenPaused(context, true)
        assertTrue(FloatingLyricsStyleStore.isAutoHideWhenPaused(context))

        FloatingLyricsStyleStore.setAutoHideWhenPaused(context, false)
        assertFalse(FloatingLyricsStyleStore.isAutoHideWhenPaused(context))
    }

}
