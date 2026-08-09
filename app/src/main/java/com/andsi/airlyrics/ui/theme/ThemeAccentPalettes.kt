package com.andsi.airlyrics.ui.theme

import com.andsi.airlyrics.core.model.ThemeAccent

/** The light/dark primary tones for each user-selectable accent. */
internal object ThemeAccentPalettes {
    fun resolve(accent: ThemeAccent, isDark: Boolean): ThemeAccentColors {
        return if (isDark) dark(accent) else light(accent)
    }

    private fun light(accent: ThemeAccent): ThemeAccentColors {
        return when (accent) {
            ThemeAccent.PINK -> ThemeAccentColors(
                accent = 0xFFF18FA9.toInt(),
                onAccent = 0xFF4B2A36.toInt(),
                accentLight = 0xFFFFB8CA.toInt()
            )
            ThemeAccent.ORANGE -> ThemeAccentColors(
                accent = 0xFFE9855A.toInt(),
                onAccent = 0xFF512313.toInt(),
                accentLight = 0xFFFFAF8D.toInt()
            )
            ThemeAccent.GREEN -> ThemeAccentColors(
                accent = 0xFF54A987.toInt(),
                onAccent = 0xFF0F3426.toInt(),
                accentLight = 0xFF86CFB0.toInt()
            )
            ThemeAccent.BLUE -> ThemeAccentColors(
                accent = 0xFF5D94E8.toInt(),
                onAccent = 0xFF172B4A.toInt(),
                accentLight = 0xFF91B9F5.toInt()
            )
            ThemeAccent.PURPLE -> ThemeAccentColors(
                accent = 0xFF9877D9.toInt(),
                onAccent = 0xFF251A3B.toInt(),
                accentLight = 0xFFBBA2EA.toInt()
            )
        }
    }

    private fun dark(accent: ThemeAccent): ThemeAccentColors {
        return when (accent) {
            ThemeAccent.PINK -> ThemeAccentColors(
                accent = 0xFFF68AAB.toInt(),
                onAccent = 0xFF40242F.toInt(),
                accentLight = 0xFFFFB3CA.toInt()
            )
            ThemeAccent.ORANGE -> ThemeAccentColors(
                accent = 0xFFF39A70.toInt(),
                onAccent = 0xFF482011.toInt(),
                accentLight = 0xFFFFB99A.toInt()
            )
            ThemeAccent.GREEN -> ThemeAccentColors(
                accent = 0xFF69C39E.toInt(),
                onAccent = 0xFF12372B.toInt(),
                accentLight = 0xFF91D9BC.toInt()
            )
            ThemeAccent.BLUE -> ThemeAccentColors(
                accent = 0xFF74A8F5.toInt(),
                onAccent = 0xFF162945.toInt(),
                accentLight = 0xFFA2C7FF.toInt()
            )
            ThemeAccent.PURPLE -> ThemeAccentColors(
                accent = 0xFFAD8DEA.toInt(),
                onAccent = 0xFF302348.toInt(),
                accentLight = 0xFFC8AEF5.toInt()
            )
        }
    }
}

internal data class ThemeAccentColors(
    val accent: Int,
    val onAccent: Int,
    val accentLight: Int
)
