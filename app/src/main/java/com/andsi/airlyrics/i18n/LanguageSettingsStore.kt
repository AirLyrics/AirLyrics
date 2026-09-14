package com.andsi.airlyrics.i18n

import android.annotation.SuppressLint
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.prefs.prefs
import java.util.Locale

object LanguageSettingsStore {
    const val MODE_SYSTEM = "system"
    const val MODE_ZH_CN = "zh-CN"
    const val MODE_ZH_TW = "zh-TW"
    const val MODE_EN = "en"

    private const val PREFS = "airlyrics_language_settings"
    private const val KEY_MODE = "language_mode"
    private const val KEY_APP_LOCALES_MIGRATED = "app_locales_migrated"

    private fun store(context: Context) = prefs(context, PREFS)

    /**
     * Connects the existing language preference to Android's per-app language APIs.
     * On Android 13+, the system setting becomes the source of truth after a one-time
     * migration. Older versions continue restoring the app's own stored preference.
     */
    fun initialize(context: Context) {
        val preferences = store(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!preferences.getBoolean(KEY_APP_LOCALES_MIGRATED, false)) {
                val legacyMode = storedMode(context)
                val systemLocales = context.getSystemService(LocaleManager::class.java).applicationLocales
                if (systemLocales.isEmpty && preferences.contains(KEY_MODE)) {
                    setApplicationLocales(context, legacyMode)
                }
                preferences.setBoolean(KEY_APP_LOCALES_MIGRATED, true, commit = true)
            }
            syncStoredMode(context, systemMode(context))
        } else {
            setApplicationLocales(context, storedMode(context))
        }
    }

    fun getMode(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return systemMode(context).also { syncStoredMode(context, it) }
        }
        return storedMode(context)
    }

    private fun storedMode(context: Context): String {
        return when (val stored = store(context).getString(KEY_MODE, MODE_SYSTEM)) {
            MODE_ZH_CN, MODE_ZH_TW, MODE_EN -> stored
            else -> MODE_SYSTEM
        }
    }

    fun setMode(context: Context, mode: String) {
        val normalized = when (mode) {
            MODE_ZH_CN, MODE_ZH_TW, MODE_EN -> mode
            else -> MODE_SYSTEM
        }
        store(context).setString(KEY_MODE, normalized)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            store(context).setBoolean(KEY_APP_LOCALES_MIGRATED, true)
        }
        setApplicationLocales(context, normalized)
    }

    private fun setApplicationLocales(context: Context, mode: String) {
        val tags = mode.takeUnless { it == MODE_SYSTEM }.orEmpty()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(tags)
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags))
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun systemMode(context: Context): String {
        val tags = context.getSystemService(LocaleManager::class.java)
            .applicationLocales
            .toLanguageTags()
        return modeFromLanguageTags(tags)
    }

    private fun syncStoredMode(context: Context, mode: String) {
        if (storedMode(context) != mode) {
            store(context).setString(KEY_MODE, mode)
        }
    }

    internal fun modeFromLanguageTags(tags: String): String {
        val primaryTag = tags.substringBefore(',').trim()
        return when {
            primaryTag.equals(MODE_ZH_TW, ignoreCase = true) -> MODE_ZH_TW
            primaryTag.equals(MODE_ZH_CN, ignoreCase = true) -> MODE_ZH_CN
            primaryTag.equals(MODE_EN, ignoreCase = true) ||
                primaryTag.startsWith("en-", ignoreCase = true) -> MODE_EN
            primaryTag.startsWith("zh-", ignoreCase = true) -> {
                val locale = Locale.forLanguageTag(primaryTag)
                if (locale.usesTraditionalChinese()) MODE_ZH_TW else MODE_ZH_CN
            }
            else -> MODE_SYSTEM
        }
    }

    @SuppressLint("AppBundleLocaleChanges")
    fun applyAppLocale(context: Context) {
        // Android 13+ applies app locales to every component through LocaleManager.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return

        val tags = when (getMode(context)) {
            MODE_ZH_CN -> MODE_ZH_CN
            MODE_ZH_TW -> MODE_ZH_TW
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
        val mode = getMode(context)
        if (mode != MODE_SYSTEM) {
            val languageRes = when (mode) {
                MODE_ZH_CN -> R.string.ui_chinese_simplified
                MODE_ZH_TW -> R.string.ui_chinese_traditional
                else -> R.string.ui_english
            }
            return context.getString(languageRes)
        }

        val systemLocale = Resources.getSystem().configuration.locales.get(0)
        val languageRes = languageNameRes(systemLocale)
        val languageName = context.getString(languageRes)
        return context.getString(R.string.ui_follow_system) + " · " + languageName
    }

    internal fun languageNameRes(locale: Locale): Int {
        if (!locale.isChineseLanguage()) {
            return R.string.ui_english
        }

        return if (locale.usesTraditionalChinese()) {
            R.string.ui_chinese_traditional
        } else {
            R.string.ui_chinese_simplified
        }
    }
}
