package com.andsi.airlyrics.core.text

import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsScriptPreference

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
        return when (preference) {
            LyricsScriptPreference.NONE -> Rendered(original, translation)
            LyricsScriptPreference.SIMPLIFIED, LyricsScriptPreference.TRADITIONAL -> {
                if (!ChineseText.isScriptConversion(original, translation)) {
                    Rendered(original, translation)
                } else {
                    Rendered(if (preference == LyricsScriptPreference.SIMPLIFIED) {
                        simplifiedCopy(original, translation)
                    } else {
                        traditionalCopy(original, translation)
                    })
                }
            }
        }
    }

    private fun simplifiedCopy(original: String, translation: String): String = when {
        !ChineseText.containsTraditional(translation) -> translation
        !ChineseText.containsTraditional(original) -> original
        // A zh-Hans translation may still keep a character such as 著 that tw2s only folds in
        // phrases; it is still the simplified copy when it carries fewer of them.
        ChineseText.traditionalCharacterCount(translation) <
            ChineseText.traditionalCharacterCount(original) -> translation
        else -> original
    }

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
