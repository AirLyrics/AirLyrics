package com.andsi.airlyrics.ui.theme

import android.graphics.Color
import com.andsi.airlyrics.core.model.ThemeAccent
import com.andsi.airlyrics.design.theme.ThemeAccentPalettes

internal object AirLyricsTheme {
    private val lightPalette = AirLyricsPalette(
        background = Color.rgb(255, 249, 243),
        surface = Color.rgb(255, 244, 236),
        surfaceLight = Color.rgb(255, 250, 246),
        card = Color.rgb(255, 255, 255),
        bubble = Color.argb(246, 255, 252, 248),
        stroke = Color.rgb(245, 221, 215),
        accent = Color.rgb(241, 143, 169),
        onAccent = Color.rgb(75, 42, 54),
        accentLight = Color.rgb(255, 184, 202),
        accentSoft = Color.rgb(159, 214, 203),
        accentPink = Color.rgb(255, 177, 197),
        accentMint = Color.rgb(150, 211, 203),
        danger = Color.rgb(198, 40, 40),
        textStrong = Color.rgb(91, 67, 76),
        text = Color.rgb(122, 94, 105),
        textMuted = Color.rgb(166, 132, 142)
    )

    private val darkPalette = AirLyricsPalette(
        background = Color.rgb(27, 23, 30),
        surface = Color.rgb(36, 30, 39),
        surfaceLight = Color.rgb(49, 40, 53),
        card = Color.rgb(43, 35, 47),
        bubble = Color.argb(248, 43, 35, 47),
        stroke = Color.rgb(75, 58, 70),
        accent = Color.rgb(246, 138, 171),
        onAccent = Color.rgb(64, 36, 47),
        accentLight = Color.rgb(255, 179, 202),
        accentSoft = Color.rgb(111, 191, 184),
        accentPink = Color.rgb(236, 126, 164),
        accentMint = Color.rgb(105, 190, 182),
        danger = Color.rgb(255, 107, 107),
        textStrong = Color.rgb(247, 229, 237),
        text = Color.rgb(224, 199, 211),
        textMuted = Color.rgb(178, 148, 164)
    )

    fun palette(isDark: Boolean, accent: ThemeAccent): AirLyricsPalette {
        val base = if (isDark) darkPalette else lightPalette
        val colors = ThemeAccentPalettes.resolve(accent, isDark)
        return base.copy(
            accent = colors.accent,
            onAccent = colors.onAccent,
            accentLight = colors.accentLight
        )
    }
}
