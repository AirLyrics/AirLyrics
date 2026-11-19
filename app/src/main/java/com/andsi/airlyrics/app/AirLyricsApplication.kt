package com.andsi.airlyrics.app

import android.app.Application
import android.content.Context
import com.andsi.airlyrics.app.platform.AppNightMode
import com.andsi.airlyrics.i18n.LanguageSettingsStore
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.ui.theme.AppearancePreferences
import com.andsi.airlyrics.ui.theme.AppearancePreferencesProvider

internal class AirLyricsApplication : Application(), AppearancePreferencesProvider {
    override fun appearancePreferences(context: Context) = AppearancePreferences(
        isDark = ThemeSettingsStore.isDark(context),
        accent = ThemeSettingsStore.getAccent(context),
        buttonStyle = ThemeSettingsStore.getButtonStyle(context)
    )

    override fun onCreate() {
        super.onCreate()
        com.andsi.airlyrics.core.text.ChineseText.ensureLoaded(this)
        LanguageSettingsStore.initialize(this)
        AppNightMode.applyStoredMode(this)
        com.andsi.airlyrics.lyrics.storage.LyricsStorage.scheduleCacheMaintenance(this)
        com.andsi.airlyrics.settings.store.FloatingLyricsFontStore.scheduleRetiredFontCleanup(this)
    }
}
