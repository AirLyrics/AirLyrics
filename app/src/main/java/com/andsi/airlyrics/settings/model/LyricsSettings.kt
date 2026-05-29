package com.andsi.airlyrics.settings.model

/** User-facing lyrics lookup configuration. */
data class LyricsSettings(
    val source: LyricsSearchSource,
    val autoSearchOnline: Boolean,
    val autoSaveLocal: Boolean
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

data class LyricsSourceOption(
    val key: String,
    val title: String,
    val description: String
)
