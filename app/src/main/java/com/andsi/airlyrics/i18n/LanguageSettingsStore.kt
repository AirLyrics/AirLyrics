package com.andsi.airlyrics.i18n

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.prefs.prefs
import java.util.Locale

object LanguageSettingsStore {
    const val MODE_SYSTEM = "system"
    const val MODE_ZH_CN = "zh-CN"
    const val MODE_EN = "en"

    private const val PREFS = "airlyrics_language_settings"
    private const val KEY_MODE = "language_mode"

    private fun store(context: Context) = prefs(context, PREFS)

    fun getMode(context: Context): String {
        return when (val stored = store(context).getString(KEY_MODE, MODE_SYSTEM)) {
            MODE_ZH_CN, MODE_EN -> stored
            else -> MODE_SYSTEM
        }
    }

    fun setMode(context: Context, mode: String) {
        val normalized = when (mode) {
            MODE_ZH_CN, MODE_EN -> mode
            else -> MODE_SYSTEM
        }
        store(context).setString(KEY_MODE, normalized)
        applyAppLocale(context)
    }

    fun applyAppLocale(context: Context) {
        val tags = when (getMode(context)) {
            MODE_ZH_CN -> MODE_ZH_CN
            MODE_EN -> MODE_EN
            else -> ""
        }
        val locale = if (tags.isBlank()) {
            Resources.getSystem().configuration.locales.get(0)
        } else {
            Locale.forLanguageTag(tags)
        }
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(locale))
        configuration.setLayoutDirection(locale)
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(configuration, context.resources.displayMetrics)
        @Suppress("DEPRECATION")
        context.applicationContext.resources.updateConfiguration(configuration, context.resources.displayMetrics)
    }

    fun currentDisplayName(context: Context): String {
        return when (getMode(context)) {
            MODE_ZH_CN -> context.getString(R.string.ui_chinese_simplified)
            MODE_EN -> "English"
            else -> {
                val languageName = if (isSystemChinese()) {
                    context.getString(R.string.ui_chinese_simplified)
                } else {
                    "English"
                }
                context.getString(R.string.ui_follow_system) + " · " + languageName
            }
        }
    }

    private fun isSystemChinese(): Boolean {
        val locale = Resources.getSystem().configuration.locales.get(0)
        return locale.language.equals(Locale.CHINESE.language, ignoreCase = true)
    }
}
