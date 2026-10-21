package com.andsi.airlyrics.core.text

import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsScriptPreference
import com.andsi.airlyrics.core.text.ChineseText

/**
 * Turns a parsed lyric line into the text the floating window shows.
 *
 * [LyricsContentDisplayMode.AUTO] adapts to the lyric's own language instead of forcing one
 * layout on every song:
 *
 * - Chinese original with a translation: show the translated line only, in simplified Chinese.
 *   Typical case: a traditional-Chinese lyric with a simplified translation — the duplicate
 *   script adds nothing, so the reader sees one clean line.
 * - Non-Chinese original with a translation: keep the original and show the translation under it.
 * - No translation: show the original, whatever its script.
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
        val isConversion = translated.isNotBlank() &&
            ChineseText.isScriptConversion(text, translated)

        return when (contentMode) {
            LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION ->
                Rendered(text, translated.takeIf { it.isNotBlank() })

            LyricsContentDisplayMode.AUTO -> renderAuto(
                text, translated, isConversion, scriptPreference
            )

            LyricsContentDisplayMode.ORIGINAL_ONLY -> Rendered(text)

            LyricsContentDisplayMode.TRANSLATION_ONLY -> Rendered(translated)
        }
    }

    private fun renderAuto(
        original: String,
        translation: String,
        isConversion: Boolean,
        preference: LyricsScriptPreference
    ): Rendered {
        if (translation.isBlank()) {
            // No translation at all: the preference can still simplify the original, which is
            // the common case for Chinese songs Apple ships without a zh-Hans translation.
            return when (preference) {
                LyricsScriptPreference.SIMPLIFIED -> Rendered(ChineseText.toSimplified(original))
                LyricsScriptPreference.TRADITIONAL, LyricsScriptPreference.NONE -> Rendered(original)
            }
        }
        if (original.isBlank()) return Rendered(translation)
        // The preference only chooses between two copies of the same line written in different
        // scripts. A real translation says something else, so both lines are always shown.
        if (!isConversion) return Rendered(original, translation)
        return when (preference) {
            LyricsScriptPreference.SIMPLIFIED ->
                Rendered(simplifiedVariant(original, translation))

            LyricsScriptPreference.TRADITIONAL ->
                Rendered(traditionalVariant(original, translation))

            LyricsScriptPreference.NONE -> Rendered(original, translation)
        }
    }

    private fun simplifiedVariant(original: String, translation: String): String =
        if (ChineseText.containsTraditional(translation)) {
            ChineseText.toSimplified(original)
        } else {
            translation
        }

    private fun traditionalVariant(original: String, translation: String): String =
        if (ChineseText.containsTraditional(original)) original else translation

    fun join(rendered: Rendered): String {
        val primary = rendered.primary.takeIf { it.isNotBlank() }.orEmpty()
        val secondary = rendered.secondary?.takeIf { it.isNotBlank() }
        return if (secondary == null) primary else "$primary\n$secondary"
    }
}
