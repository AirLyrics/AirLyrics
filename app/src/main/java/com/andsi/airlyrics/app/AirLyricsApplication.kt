package com.andsi.airlyrics.app

import android.app.Application
import com.andsi.airlyrics.app.platform.AppNightMode
import com.andsi.airlyrics.i18n.LanguageSettingsStore

class AirLyricsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LanguageSettingsStore.initialize(this)
        AppNightMode.applyStoredMode(this)
    }
}
