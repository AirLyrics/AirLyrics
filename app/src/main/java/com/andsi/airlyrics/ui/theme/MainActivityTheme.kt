package com.andsi.airlyrics.ui.theme

import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.settings.store.ThemeSettingsStore

internal val MainActivity.airLyricsPalette: AirLyricsPalette
    get() = AirLyricsTheme.palette(ThemeSettingsStore.isDark(this))

internal val MainActivity.colorBackground: Int
    get() = airLyricsPalette.background
internal val MainActivity.colorSurface: Int
    get() = airLyricsPalette.surface
internal val MainActivity.colorSurfaceLight: Int
    get() = airLyricsPalette.surfaceLight
internal val MainActivity.colorCard: Int
    get() = airLyricsPalette.card
internal val MainActivity.colorBubble: Int
    get() = airLyricsPalette.bubble
internal val MainActivity.colorStroke: Int
    get() = airLyricsPalette.stroke
internal val MainActivity.colorAccent: Int
    get() = airLyricsPalette.accent
internal val MainActivity.colorAccentLight: Int
    get() = airLyricsPalette.accentLight
internal val MainActivity.colorAccentSoft: Int
    get() = airLyricsPalette.accentSoft
internal val MainActivity.colorAccentPink: Int
    get() = airLyricsPalette.accentPink
internal val MainActivity.colorAccentMint: Int
    get() = airLyricsPalette.accentMint
internal val MainActivity.colorTextStrong: Int
    get() = airLyricsPalette.textStrong
internal val MainActivity.colorText: Int
    get() = airLyricsPalette.text
internal val MainActivity.colorTextMuted: Int
    get() = airLyricsPalette.textMuted
