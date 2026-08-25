package com.andsi.airlyrics.ui.navigation

internal enum class Page { MEDIA, FLOATING, SETTINGS }

internal enum class SettingsSubPage { HOME, SYSTEM, LYRICS, SAVED_LYRICS, ABOUT }

internal fun SettingsSubPage.parentPage(): SettingsSubPage? {
    return when (this) {
        SettingsSubPage.HOME -> null
        SettingsSubPage.SAVED_LYRICS -> SettingsSubPage.LYRICS
        SettingsSubPage.SYSTEM,
        SettingsSubPage.LYRICS,
        SettingsSubPage.ABOUT -> SettingsSubPage.HOME
    }
}
