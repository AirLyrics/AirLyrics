package com.andsi.airlyrics.ui.pages

import android.view.View
import com.andsi.airlyrics.MainActivity
import com.andsi.airlyrics.MainActivity.SettingsSubPage
import com.andsi.airlyrics.ui.pages.settings.createAboutSettingsPage
import com.andsi.airlyrics.ui.pages.settings.createFloatingSettingsPage
import com.andsi.airlyrics.ui.pages.settings.createLyricsSettingsPage
import com.andsi.airlyrics.ui.pages.settings.createSettingsHomePage
import com.andsi.airlyrics.ui.pages.settings.createSystemSettingsPage
import com.andsi.airlyrics.ui.pages.settings.createThemeSettingsPage

internal fun MainActivity.createSettingsPage(): View {
    return when (settingsSubPage) {
        SettingsSubPage.HOME -> createSettingsHomePage()
        SettingsSubPage.SYSTEM -> createSystemSettingsPage()
        SettingsSubPage.THEME -> createThemeSettingsPage()
        SettingsSubPage.FLOATING -> createFloatingSettingsPage()
        SettingsSubPage.LYRICS -> createLyricsSettingsPage()
        SettingsSubPage.ABOUT -> createAboutSettingsPage()
    }
}
