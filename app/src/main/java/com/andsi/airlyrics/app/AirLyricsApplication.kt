package com.andsi.airlyrics.app

import android.app.Application
import com.andsi.airlyrics.app.platform.AppNightMode

class AirLyricsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppNightMode.applyStoredMode(this)
    }
}
