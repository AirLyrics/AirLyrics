package com.andsi.airlyrics.ui.navigation

internal enum class Page { MEDIA, FLOATING, SETTINGS }

internal enum class SettingsSubPage { HOME, SYSTEM, LYRICS, SAVED_LYRICS, ABOUT, APPLE_ACCOUNT }

internal fun SettingsSubPage.parentPage(): SettingsSubPage? {
    return when (this) {
        SettingsSubPage.HOME -> null
        SettingsSubPage.APPLE_ACCOUNT,
        SettingsSubPage.SAVED_LYRICS -> SettingsSubPage.LYRICS
        SettingsSubPage.SYSTEM,
        SettingsSubPage.LYRICS,
        SettingsSubPage.ABOUT -> SettingsSubPage.HOME
    }
}
