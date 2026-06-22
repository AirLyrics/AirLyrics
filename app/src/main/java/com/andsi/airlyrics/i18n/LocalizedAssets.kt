package com.andsi.airlyrics.i18n

import android.content.Context
import java.util.Locale

/**
 * Reads localized text assets such as Markdown help documents.
 *
 * Short UI strings should stay in Android string resources. This helper is for
 * content stored as files under assets, for example help/lyrics_format.zh-CN.md.
 */
internal fun Context.localizedAssetText(
    baseName: String,
    extension: String = "md",
    fallback: String = ""
): String {
    val tag = resources.configuration.locales[0]?.toLanguageTag()
        ?: Locale.getDefault().toLanguageTag()
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
