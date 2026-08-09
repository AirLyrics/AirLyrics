package com.andsi.airlyrics.ui.theme

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.core.model.ThemeAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AirLyricsThemeTest {
    @Test
    fun iconForegroundUsesEightyPercentOnAccentAlpha() {
        val onAccent = AirLyricsTheme.palette(isDark = false, ThemeAccent.PINK).onAccent
        val iconColor = iconColorOnAccent(onAccent)

        assertEquals(204, Color.alpha(iconColor))
        assertEquals(Color.red(onAccent), Color.red(iconColor))
        assertEquals(Color.green(onAccent), Color.green(iconColor))
        assertEquals(Color.blue(onAccent), Color.blue(iconColor))
    }

    @Test
    fun everyAccentHasDistinctLightAndDarkPrimaryColors() {
        val lightColors = ThemeAccent.entries.map { AirLyricsTheme.palette(isDark = false, it).accent }
        val darkColors = ThemeAccent.entries.map { AirLyricsTheme.palette(isDark = true, it).accent }

        assertEquals(ThemeAccent.entries.size, lightColors.distinct().size)
        assertEquals(ThemeAccent.entries.size, darkColors.distinct().size)
        ThemeAccent.entries.forEach { accent ->
            assertNotEquals(
                AirLyricsTheme.palette(isDark = false, accent).accent,
                AirLyricsTheme.palette(isDark = true, accent).accent
            )
        }
    }

    @Test
    fun changingAccentOnlyChangesPrimaryAccentRoles() {
        val pink = AirLyricsTheme.palette(isDark = false, ThemeAccent.PINK)
        val blue = AirLyricsTheme.palette(isDark = false, ThemeAccent.BLUE)

        assertNotEquals(pink.accent, blue.accent)
        assertNotEquals(pink.accentLight, blue.accentLight)
        assertNotEquals(pink.onAccent, blue.onAccent)
        assertEquals(pink.background, blue.background)
        assertEquals(pink.accentSoft, blue.accentSoft)
        assertEquals(pink.accentPink, blue.accentPink)
        assertEquals(pink.accentMint, blue.accentMint)
    }

    @Test
    fun accentForegroundsMeetNormalTextContrast() {
        listOf(false, true).forEach { isDark ->
            ThemeAccent.entries.forEach { accent ->
                val palette = AirLyricsTheme.palette(isDark, accent)
                assertTrue(
                    "$accent foreground contrast was below 4.5 in isDark=$isDark",
                    ColorUtils.calculateContrast(palette.onAccent, palette.accent) >= 4.5
                )
            }
        }
    }
}
