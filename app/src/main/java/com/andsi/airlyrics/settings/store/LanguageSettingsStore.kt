package com.andsi.airlyrics.settings.store

import android.content.Context
import com.andsi.airlyrics.R
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import java.util.Locale

internal object LanguageSettingsStore {
    const val MODE_SYSTEM = "system"
    const val MODE_ZH_CN = "zh-CN"
    const val MODE_EN = "en"

    private const val PREFS = "airlyrics_language_settings"
    private const val KEY_MODE = "language_mode"

    fun getMode(context: Context): String {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, MODE_SYSTEM)
        return when (stored) {
            MODE_ZH_CN, MODE_EN -> stored
            else -> MODE_SYSTEM
        }
    }

    fun setMode(context: Context, mode: String) {
        val normalized = when (mode) {
            MODE_ZH_CN, MODE_EN -> mode
            else -> MODE_SYSTEM
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_MODE, normalized)
        }
        applyAppLocale(context)
    }

    fun applyAppLocale(context: Context) {
        val tags = when (getMode(context)) {
            MODE_ZH_CN -> MODE_ZH_CN
            MODE_EN -> MODE_EN
            else -> ""
        }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags))

        val locale = if (tags.isBlank()) {
            context.resources.configuration.locales.get(0)
        } else {
            Locale.forLanguageTag(tags)
        }
        Locale.setDefault(locale)
        val configuration = context.resources.configuration
        configuration.setLocale(locale)
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(configuration, context.resources.displayMetrics)
    }

    fun currentDisplayName(context: Context): String {
        return when (getMode(context)) {
            MODE_ZH_CN -> context.getString(R.string.ui_chinese_simplified)
            MODE_EN -> "English"
            else -> {
                val languageName = if (isSystemChinese(context)) {
                    context.getString(R.string.ui_chinese_simplified)
                } else {
                    "English"
                }
                context.getString(R.string.ui_follow_system) + " · " + languageName
            }
        }
    }

    private fun isSystemChinese(context: Context): Boolean {
        val locale = context.resources.configuration.locales.get(0)
        return locale.language.equals(Locale.CHINESE.language, ignoreCase = true)
    }
}
