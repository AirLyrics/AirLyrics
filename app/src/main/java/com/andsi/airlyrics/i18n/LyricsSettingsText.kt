package com.andsi.airlyrics.i18n

import android.content.Context
import com.andsi.airlyrics.settings.model.LyricsContentDisplayMode
import com.andsi.airlyrics.settings.model.LyricsLineDisplayMode
import com.andsi.airlyrics.settings.model.LyricsSearchSource
import com.andsi.airlyrics.settings.model.LyricsSwitchAnimationMode

internal fun Context.localizedLyricsSourceTitle(source: LyricsSearchSource): String = when (source) {
    LyricsSearchSource.LOCAL_ONLY -> tr("只使用本地", "Local only")
    LyricsSearchSource.NETEASE -> tr("网易云音乐", "NetEase Cloud Music")
    LyricsSearchSource.MUSIXMATCH -> "Musixmatch"
}

internal fun Context.localizedLyricsSourceHint(source: LyricsSearchSource): String = when (source) {
    LyricsSearchSource.LOCAL_ONLY -> tr("只读取本地歌词", "Read local lyrics only")
    LyricsSearchSource.NETEASE -> tr("适合中国用户", "Good for Chinese songs")
    LyricsSearchSource.MUSIXMATCH -> tr(
        "适合国际用户，依据您的系统语言来自动获取翻译（如果有的话）",
        "Good for international songs; uses your system language for translations when available"
    )
}

internal fun Context.localizedLyricsContentModeTitle(mode: LyricsContentDisplayMode): String = when (mode) {
    LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION -> tr("原文 + 翻译", "Original + translation")
    LyricsContentDisplayMode.ORIGINAL_ONLY -> tr("仅原文", "Original only")
    LyricsContentDisplayMode.TRANSLATION_ONLY -> tr("仅翻译", "Translation only")
}

internal fun Context.localizedLyricsLineModeTitle(mode: LyricsLineDisplayMode): String = when (mode) {
    LyricsLineDisplayMode.CURRENT_ONLY -> tr("当前句", "Current line")
    LyricsLineDisplayMode.PREVIOUS_AND_CURRENT -> tr("上一句 + 当前句", "Previous + current")
    LyricsLineDisplayMode.CURRENT_AND_NEXT -> tr("当前句 + 下一句", "Current + next")
    LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> tr("上 + 当前 + 下", "Previous + current + next")
}

internal fun Context.localizedLyricsSwitchAnimationTitle(mode: LyricsSwitchAnimationMode): String = when (mode) {
    LyricsSwitchAnimationMode.NONE -> tr("关闭", "Off")
    LyricsSwitchAnimationMode.FADE -> tr("柔和淡入", "Fade")
    LyricsSwitchAnimationMode.SLIDE_UP -> tr("上滑淡入", "Slide up")
    LyricsSwitchAnimationMode.SCALE_FADE -> tr("轻微缩放", "Scale")
}
