package com.andsi.airlyrics.settings.store

import android.content.Context
import android.os.Build
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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, normalized)
            .apply()
    }

    fun isChinese(context: Context): Boolean {
        return when (getMode(context)) {
            MODE_ZH_CN -> true
            MODE_EN -> false
            else -> isSystemChinese(context)
        }
    }

    fun currentDisplayName(context: Context): String {
        return when (getMode(context)) {
            MODE_ZH_CN -> "简体中文"
            MODE_EN -> "English"
            else -> if (isSystemChinese(context)) "跟随系统 · 简体中文" else "System · English"
        }
    }

    private fun isSystemChinese(context: Context): Boolean {
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales.get(0)
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }
        return locale.language.equals(Locale.CHINESE.language, ignoreCase = true)
    }
}
