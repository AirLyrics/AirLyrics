package com.andsi.airlyrics.ui.theme

import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.settings.store.ThemeSettingsStore

internal val MainUiHost.airLyricsPalette: AirLyricsPalette
    get() = AirLyricsTheme.palette(ThemeSettingsStore.isDark(this))

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
internal val MainUiHost.colorAccentLight: Int
    get() = airLyricsPalette.accentLight
internal val MainUiHost.colorAccentSoft: Int
    get() = airLyricsPalette.accentSoft
internal val MainUiHost.colorAccentPink: Int
    get() = airLyricsPalette.accentPink
internal val MainUiHost.colorAccentMint: Int
    get() = airLyricsPalette.accentMint
internal val MainUiHost.colorTextStrong: Int
    get() = airLyricsPalette.textStrong
internal val MainUiHost.colorText: Int
    get() = airLyricsPalette.text
internal val MainUiHost.colorTextMuted: Int
    get() = airLyricsPalette.textMuted
