package com.andsi.airlyrics.core.text

import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsScriptPreference

/**
 * Turns a parsed lyric line into the text the floating window shows.
 *
 * The three content modes are literal: [LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION] shows
 * the original and, when one exists, the translation underneath.
 *
 * The script preference only decides which copy of a *duplicate* line to keep: when a lyric ships
 * the same sentence twice, once in traditional and once in simplified characters, the reader does
 * not need both. It never rewrites a line that exists in a single script — a traditional-only
 * lyric stays traditional, whatever the preference says.
 */
object LyricsDisplayPolicy {

    data class Rendered(val primary: String, val secondary: String? = null) {
        fun isBlank(): Boolean = primary.isBlank() && secondary.isNullOrBlank()
    }

    fun render(
        contentMode: LyricsContentDisplayMode,
        original: String,
        translation: String?,
        scriptPreference: LyricsScriptPreference = LyricsScriptPreference.default
    ): Rendered {
        val text = original.trim()
        val translated = translation.orEmpty().trim()
        return when (contentMode) {
            LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION ->
                renderOriginalWithTranslation(text, translated, scriptPreference)

            LyricsContentDisplayMode.ORIGINAL_ONLY -> Rendered(text)

            LyricsContentDisplayMode.TRANSLATION_ONLY -> Rendered(translated)
        }
    }

    private fun renderOriginalWithTranslation(
        original: String,
        translation: String,
        preference: LyricsScriptPreference
    ): Rendered {
        if (translation.isBlank()) return Rendered(original)
        if (original.isBlank()) return Rendered(translation)
        // A real translation says something else, so both lines stay. Only a duplicate pair — the
        // same sentence in two scripts — can collapse into a single line.
        if (preference == LyricsScriptPreference.NONE) return Rendered(original, translation)
        if (!ChineseText.isScriptConversion(original, translation)) return Rendered(original, translation)
        return Rendered(
            when (preference) {
                LyricsScriptPreference.SIMPLIFIED -> simplifiedCopy(original, translation)
                LyricsScriptPreference.TRADITIONAL -> traditionalCopy(original, translation)
                LyricsScriptPreference.NONE -> original
            }
        )
    }

    /** Keeps an existing simplified copy; a traditional-only line is left untouched. */
    private fun simplifiedCopy(original: String, translation: String): String = when {
        !ChineseText.containsTraditional(translation) -> translation
        !ChineseText.containsTraditional(original) -> original
        else -> original
    }

    /** Keeps an existing traditional copy; a simplified-only line is left untouched. */
    private fun traditionalCopy(original: String, translation: String): String = when {
        ChineseText.containsTraditional(original) -> original
        ChineseText.containsTraditional(translation) -> translation
        else -> original
    }

    fun join(rendered: Rendered): String {
        val primary = rendered.primary.takeIf { it.isNotBlank() }.orEmpty()
        val secondary = rendered.secondary?.takeIf { it.isNotBlank() }
        return if (secondary == null) primary else "$primary\n$secondary"
    }
}
