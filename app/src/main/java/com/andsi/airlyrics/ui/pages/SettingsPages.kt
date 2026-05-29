package com.andsi.airlyrics.ui.pages

import android.view.View
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.app.*
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import com.andsi.airlyrics.ui.pages.settings.createAboutSettingsPage
import com.andsi.airlyrics.ui.pages.settings.createLyricsSettingsPage
import com.andsi.airlyrics.ui.pages.settings.createSettingsHomePage
import com.andsi.airlyrics.ui.pages.settings.createSystemSettingsPage
import com.andsi.airlyrics.ui.pages.settings.createThemeSettingsPage

internal fun createSettingsPage(activity: MainActivity): View  = with(activity) createSettingsPage@ {
    return when (settingsSubPage) {
        SettingsSubPage.HOME -> createSettingsHomePage(activity)
        SettingsSubPage.SYSTEM -> createSystemSettingsPage(activity)
        SettingsSubPage.THEME -> createThemeSettingsPage(activity)
        SettingsSubPage.LYRICS -> createLyricsSettingsPage(activity)
        SettingsSubPage.ABOUT -> createAboutSettingsPage(activity)
    }
}
