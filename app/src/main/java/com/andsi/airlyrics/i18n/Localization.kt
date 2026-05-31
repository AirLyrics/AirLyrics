package com.andsi.airlyrics.i18n

import android.content.Context
import com.andsi.airlyrics.settings.store.LanguageSettingsStore
import java.util.Locale

/** Locale helpers for AirLyrics.
 *
 * UI strings live in Android resource XML files. This file
 * only keeps shared locale utilities and a tiny display helper for
 * component APIs that accept already-localized CharSequence values.
 */
internal object AirLocalizer {
    fun isChinese(context: Context): Boolean {
        return LanguageSettingsStore.isChinese(context)
    }

    fun languageTag(context: Context): String {
        return context.resources.configuration.locales[0]?.toLanguageTag()
            ?: Locale.getDefault().toLanguageTag()
    }

    fun text(context: Context, value: CharSequence?): CharSequence {
        return value ?: ""
    }
}

internal fun Context.displayText(value: CharSequence?): CharSequence = AirLocalizer.text(this, value)

internal fun Context.localizedAssetText(baseName: String, extension: String = "md", fallback: String = ""): String {
    val tag = AirLocalizer.languageTag(this)
    val candidates = listOf(
        "$baseName.$tag.$extension",
        "$baseName.${tag.substringBefore('-')}.$extension",
        "$baseName.en.$extension"
    ).distinct()
    for (candidate in candidates) {
        val text = runCatching {
            assets.open(candidate).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
        if (!text.isNullOrBlank()) return text.trim()
    }
    return fallback
}
