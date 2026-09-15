package com.andsi.airlyrics.settings.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.i18n.LanguageSettingsStore
import org.junit.After
import org.junit.Before

abstract class SettingsStoreTestBase {
    protected lateinit var context: Context

    @Before
    fun setUpSettingsStoreTestBase() {
        context = ApplicationProvider.getApplicationContext()
        clearPreferences()
    }

    @After
    fun tearDownSettingsStoreTestBase() {
        clearPreferences()
        LanguageSettingsStore.setMode(context, LanguageSettingsStore.MODE_SYSTEM)
    }

    private fun clearPreferences() {
        listOf(
            "floating_lyrics_style",
            "lyrics_offset_store",
            "floating_quick_control",
            "app_theme",
            "app_settings",
            "airlyrics_language_settings"
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }
}
