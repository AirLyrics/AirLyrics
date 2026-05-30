package com.andsi.airlyrics.settings.model

/** User-facing lyrics lookup and display configuration. */
data class LyricsSettings(
    val source: LyricsSearchSource,
    val autoSearchOnline: Boolean,
    val autoSaveLocal: Boolean,
    val contentDisplayMode: LyricsContentDisplayMode = LyricsContentDisplayMode.default,
    val lineDisplayMode: LyricsLineDisplayMode = LyricsLineDisplayMode.default,
    val switchAnimationMode: LyricsSwitchAnimationMode = LyricsSwitchAnimationMode.default,
    val karaokeLyricsEnabled: Boolean = false
)

/** The user's selected online lookup source. Local lyrics are always checked first. */
enum class LyricsSearchSource(
    val key: String,
    val title: String,
    val description: String
) {
    LOCAL_ONLY(
        key = "local_only",
        title = "只使用本地",
        description = "不联网查找，只读取已经导入或保存的 .lrc。"
    ),
    NETEASE(
        key = "netease",
        title = "网易云音乐",
        description = "适合中文歌曲，本地没有歌词时从网易云匹配歌词。"
    ),
    MUSIXMATCH(
        key = "musixmatch",
        title = "Musixmatch",
        description = "适合海外歌曲，本地没有歌词时从 Musixmatch 匹配歌词。"
    );

    companion object {
        val default: LyricsSearchSource = NETEASE

        fun fromKey(key: String?): LyricsSearchSource {
            return entries.firstOrNull { it.key == key } ?: default
        }
    }
}

/** Controls which lyric text should be rendered when translations are available. */
enum class LyricsContentDisplayMode(
    val key: String,
    val title: String,
    val description: String
) {
    ORIGINAL_WITH_TRANSLATION(
        key = "original_with_translation",
        title = "原文 + 翻译",
        description = "优先显示原文，并在下一行显示翻译。"
    ),
    ORIGINAL_ONLY(
        key = "original_only",
        title = "仅原文",
        description = "只显示歌词原文，界面更清爽。"
    ),
    TRANSLATION_ONLY(
        key = "translation_only",
        title = "仅翻译",
        description = "只显示翻译；没有翻译时会提示当前歌词没有翻译。"
    );

    companion object {
        val default: LyricsContentDisplayMode = ORIGINAL_WITH_TRANSLATION

        fun fromKey(key: String?): LyricsContentDisplayMode {
            return entries.firstOrNull { it.key == key } ?: default
        }
    }
}

/** Controls how many neighboring lyric lines should be rendered around the current line. */
enum class LyricsLineDisplayMode(
    val key: String,
    val title: String,
    val description: String
) {
    CURRENT_ONLY(
        key = "current_only",
        title = "当前句",
        description = "只显示正在播放的这一句。"
    ),
    PREVIOUS_AND_CURRENT(
        key = "previous_and_current",
        title = "上一句 + 当前句",
        description = "显示上一句和当前句，方便跟读。"
    ),
    CURRENT_AND_NEXT(
        key = "current_and_next",
        title = "当前句 + 下一句",
        description = "显示当前句和下一句，提前看到下一行。"
    ),
    PREVIOUS_CURRENT_NEXT(
        key = "previous_current_next",
        title = "上 + 当前 + 下",
        description = "同时显示上一句、当前句和下一句。"
    );

    companion object {
        val default: LyricsLineDisplayMode = CURRENT_ONLY

        fun fromKey(key: String?): LyricsLineDisplayMode {
            return entries.firstOrNull { it.key == key } ?: default
        }
    }
}


/** Controls the animation used when the floating lyric text changes to a new line. */
enum class LyricsSwitchAnimationMode(
    val key: String,
    val title: String,
    val description: String
) {
    NONE(
        key = "none",
        title = "关闭",
        description = "歌词切换时直接更新文字，最稳定省电。"
    ),
    FADE(
        key = "fade",
        title = "柔和淡入",
        description = "新歌词轻轻淡入，适合安静场景。"
    ),
    SLIDE_UP(
        key = "slide_up",
        title = "上滑淡入",
        description = "新歌词从下方向上浮现，更有滚动感。"
    ),
    SCALE_FADE(
        key = "scale_fade",
        title = "轻微缩放",
        description = "新歌词轻微放大淡入，提示感更明显。"
    );

    companion object {
        val default: LyricsSwitchAnimationMode = SLIDE_UP

        fun fromKey(key: String?): LyricsSwitchAnimationMode {
            return entries.firstOrNull { it.key == key } ?: default
        }
    }
}


data class LyricsSourceOption(
    val key: String,
    val title: String,
    val description: String
)
