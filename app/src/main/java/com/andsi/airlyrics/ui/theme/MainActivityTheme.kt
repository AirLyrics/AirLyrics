package com.andsi.airlyrics.ui.theme

import androidx.annotation.ColorInt
import com.andsi.airlyrics.core.color.AirColorUtils
import com.andsi.airlyrics.ui.model.MainUiHost

private const val ON_ACCENT_ICON_ALPHA = 204

internal val MainUiHost.airLyricsPalette: AirLyricsPalette
    get() = AirLyricsTheme.palette(isDarkTheme(), themeAccent())

internal val MainUiHost.colorBackground: Int
    get() = airLyricsPalette.background
internal val MainUiHost.colorSurface: Int
    get() = airLyricsPalette.surface
internal val MainUiHost.colorSurfaceLight: Int
    get() = airLyricsPalette.surfaceLight
internal val MainUiHost.colorCard: Int
    get() = airLyricsPalette.card
internal val MainUiHost.colorBubble: Int
    get() = airLyricsPalette.bubble
internal val MainUiHost.colorStroke: Int
    get() = airLyricsPalette.stroke
internal val MainUiHost.colorAccent: Int
    get() = airLyricsPalette.accent
internal val MainUiHost.colorOnAccent: Int
    get() = airLyricsPalette.onAccent
internal val MainUiHost.colorIconOnAccent: Int
    get() = iconColorOnAccent(colorOnAccent)
internal val MainUiHost.colorAccentLight: Int
    get() = airLyricsPalette.accentLight
internal val MainUiHost.colorAccentSoft: Int
    get() = airLyricsPalette.accentSoft
internal val MainUiHost.colorAccentMint: Int
    get() = airLyricsPalette.accentMint
internal val MainUiHost.colorDanger: Int
    get() = airLyricsPalette.danger
internal val MainUiHost.colorTextStrong: Int
    get() = airLyricsPalette.textStrong
internal val MainUiHost.colorText: Int
    get() = airLyricsPalette.text
internal val MainUiHost.colorTextMuted: Int
    get() = airLyricsPalette.textMuted

@ColorInt
internal fun iconColorOnAccent(@ColorInt colorOnAccent: Int): Int {
    return AirColorUtils.withAlpha(colorOnAccent, ON_ACCENT_ICON_ALPHA)
}
