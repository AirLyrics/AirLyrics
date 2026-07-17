package com.andsi.airlyrics.ui.theme

import android.graphics.Color
import android.view.Gravity
import com.andsi.airlyrics.core.model.FloatingLyricsStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AirColorUtilsTest {
    @Test
    fun withAlpha_replacesAndClampsAlpha() {
        val color = Color.rgb(10, 20, 30)

        assertEquals(Color.argb(128, 10, 20, 30), AirColorUtils.withAlpha(color, 128))
        assertEquals(Color.argb(0, 10, 20, 30), AirColorUtils.withAlpha(color, -1))
        assertEquals(Color.argb(255, 10, 20, 30), AirColorUtils.withAlpha(color, 300))
    }

    @Test
    fun opaqueRgb_dropsAlpha() {
        val color = Color.argb(90, 10, 20, 30)

        assertEquals(Color.rgb(10, 20, 30), AirColorUtils.opaqueRgb(color))
    }

    @Test
    fun isDarkColor_usesLuminanceThreshold() {
        assertTrue(AirColorUtils.isDarkColor(Color.rgb(20, 20, 20)))
        assertFalse(AirColorUtils.isDarkColor(Color.rgb(240, 240, 240)))
    }

    @Test
    fun colorSummary_includesRgbaChannels() {
        val color = Color.argb(40, 10, 20, 30)

        assertEquals("R10 G20 B30 A40", AirColorUtils.colorSummary(color))
    }

    @Test
    fun backgroundColorWithAlpha_usesStyleBackgroundAlpha() {
        val style = FloatingLyricsStyle(
            presetName = "test",
            textSizeSp = 20f,
            textColor = Color.WHITE,
            karaokeHighlightColor = Color.CYAN,
            shadowColor = Color.BLACK,
            shadowRadius = 2f,
            backgroundEnabled = true,
            backgroundColor = Color.rgb(10, 20, 30),
            backgroundAlpha = 80,
            cornerRadiusDp = 12,
            paddingHorizontalDp = 8,
            paddingVerticalDp = 4,
            maxWidthPercent = 80,
            gravity = Gravity.CENTER
        )

        assertEquals(Color.argb(80, 10, 20, 30), AirColorUtils.backgroundColorWithAlpha(style))
    }
}
