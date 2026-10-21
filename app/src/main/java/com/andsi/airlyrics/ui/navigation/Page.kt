package com.andsi.airlyrics.ui.navigation

internal enum class Page { MEDIA, FLOATING, SETTINGS }

internal enum class SettingsSubPage { HOME, SYSTEM, LYRICS, SAVED_LYRICS, ABOUT, APPLE_ACCOUNT, APPLE_LOGIN }

internal fun SettingsSubPage.parentPage(): SettingsSubPage? {
    return when (this) {
        SettingsSubPage.HOME -> null
        SettingsSubPage.SAVED_LYRICS -> SettingsSubPage.LYRICS
        SettingsSubPage.APPLE_LOGIN -> SettingsSubPage.APPLE_ACCOUNT
        SettingsSubPage.SYSTEM,
        SettingsSubPage.LYRICS,
        SettingsSubPage.ABOUT,
        SettingsSubPage.APPLE_ACCOUNT -> SettingsSubPage.HOME
    }
}
