package com.andsi.airlyrics.core.model

/** User-facing lyrics lookup and display configuration. */
data class LyricsSettings(
    /** Selected online lookup sources in priority order. Empty disables online lookup. */
    val plainLyricsSearchSources: List<PlainLyricsSearchSource>,
    val autoSearchOnline: Boolean,
    val autoSaveLocal: Boolean,
    val contentDisplayMode: LyricsContentDisplayMode = LyricsContentDisplayMode.default,
    val scriptPreference: LyricsScriptPreference = LyricsScriptPreference.default,
    val lineDisplayMode: LyricsLineDisplayMode = LyricsLineDisplayMode.default,
    val switchAnimationMode: LyricsSwitchAnimationMode = LyricsSwitchAnimationMode.default,
    val wordByWordLyricsEnabled: Boolean = false
)

/** An online lookup source. Local lyrics are always checked first. */
enum class PlainLyricsSearchSource(val key: String, val supportsWordTiming: Boolean = false) {
    /** Persisted compatibility value. New UI models local-only behavior with [LyricsSettings.autoSearchOnline]. */
    LOCAL_ONLY("local_only"),
    NETEASE("netease"),
    MUSIXMATCH("musixmatch"),
    LRCLIB("lrclib"),
    SPICY("spicy", supportsWordTiming = true),
    APPLE("apple", supportsWordTiming = true);

    companion object {
        val default: PlainLyricsSearchSource = NETEASE
        val onlineSources: List<PlainLyricsSearchSource> = listOf(NETEASE, MUSIXMATCH, LRCLIB, SPICY, APPLE)

        fun fromKeyOrNull(key: String?): PlainLyricsSearchSource? {
            return entries.firstOrNull { it.key == key }
        }

    }
}

/** Controls which lyric text should be rendered when translations are available. */
enum class LyricsContentDisplayMode(val key: String) {
    ORIGINAL_WITH_TRANSLATION("original_with_translation"),
    ORIGINAL_ONLY("original_only"),
    TRANSLATION_ONLY("translation_only"),

    /** Follows the lyric's own language and the script preference picked for auto mode. */
    AUTO("auto");

    companion object {
        val default: LyricsContentDisplayMode = AUTO

        fun fromKey(key: String?): LyricsContentDisplayMode {
            return entries.firstOrNull { it.key == key } ?: default
        }
    }
}

/**
 * Which Chinese script to prefer when a lyric offers both a traditional original and a
 * simplified translation.
 *
 * [SIMPLIFIED] shows the simplified text whenever one exists; [TRADITIONAL] keeps the original
 * script and drops a translation that is only the same line in simplified characters; [NONE]
 * leaves both lines untouched.
 */
enum class LyricsScriptPreference(val key: String) {
    SIMPLIFIED("simplified"),
    TRADITIONAL("traditional"),
    NONE("none");

    companion object {
        val default: LyricsScriptPreference = SIMPLIFIED

        fun fromKey(key: String?): LyricsScriptPreference {
            return entries.firstOrNull { it.key == key } ?: default
        }
    }
}

/** Controls how many neighboring lyric lines should be rendered around the current line. */
enum class LyricsLineDisplayMode(val key: String) {
    CURRENT_ONLY("current_only"),
    PREVIOUS_AND_CURRENT("previous_and_current"),
    CURRENT_AND_NEXT("current_and_next"),
    PREVIOUS_CURRENT_NEXT("previous_current_next");

    companion object {
        val default: LyricsLineDisplayMode = CURRENT_ONLY

        fun fromKey(key: String?): LyricsLineDisplayMode {
            return entries.firstOrNull { it.key == key } ?: default
        }
    }
}

/** Controls the animation used when the floating lyric text changes to a new line. */
enum class LyricsSwitchAnimationMode(val key: String) {
    NONE("none"),
    FADE("fade"),
    SLIDE_UP("slide_up"),
    SCALE_FADE("scale_fade");

    companion object {
        val default: LyricsSwitchAnimationMode = FADE

        fun fromKey(key: String?): LyricsSwitchAnimationMode {
            return entries.firstOrNull { it.key == key } ?: default
        }
    }
}
