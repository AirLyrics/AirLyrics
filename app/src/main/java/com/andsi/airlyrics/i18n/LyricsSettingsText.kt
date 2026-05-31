package com.andsi.airlyrics.i18n

import com.andsi.airlyrics.R

import android.content.Context
import com.andsi.airlyrics.settings.model.LyricsContentDisplayMode
import com.andsi.airlyrics.settings.model.LyricsLineDisplayMode
import com.andsi.airlyrics.settings.model.LyricsSearchSource
import com.andsi.airlyrics.settings.model.LyricsSwitchAnimationMode

internal fun Context.localizedLyricsSourceTitle(source: LyricsSearchSource): String = when (source) {
    LyricsSearchSource.LOCAL_ONLY -> getString(R.string.ui_local_only)
    LyricsSearchSource.NETEASE -> getString(R.string.ui_netease_cloud_music)
    LyricsSearchSource.MUSIXMATCH -> getString(R.string.provider_musixmatch)
}

internal fun Context.localizedLyricsSourceHint(source: LyricsSearchSource): String = when (source) {
    LyricsSearchSource.LOCAL_ONLY -> getString(R.string.ui_read_local_lyrics_only)
    LyricsSearchSource.NETEASE -> getString(R.string.ui_good_for_chinese_songs)
    LyricsSearchSource.MUSIXMATCH -> getString(R.string.ui_musixmatch_source_hint)
}

internal fun Context.localizedLyricsContentModeTitle(mode: LyricsContentDisplayMode): String = when (mode) {
    LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION -> getString(R.string.ui_original_translation)
    LyricsContentDisplayMode.ORIGINAL_ONLY -> getString(R.string.ui_original_only)
    LyricsContentDisplayMode.TRANSLATION_ONLY -> getString(R.string.ui_translation_only)
}

internal fun Context.localizedLyricsLineModeTitle(mode: LyricsLineDisplayMode): String = when (mode) {
    LyricsLineDisplayMode.CURRENT_ONLY -> getString(R.string.ui_current_line)
    LyricsLineDisplayMode.PREVIOUS_AND_CURRENT -> getString(R.string.ui_previous_current)
    LyricsLineDisplayMode.CURRENT_AND_NEXT -> getString(R.string.ui_current_next)
    LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> getString(R.string.ui_previous_current_next)
}

internal fun Context.localizedLyricsSwitchAnimationTitle(mode: LyricsSwitchAnimationMode): String = when (mode) {
    LyricsSwitchAnimationMode.NONE -> getString(R.string.ui_off)
    LyricsSwitchAnimationMode.FADE -> getString(R.string.ui_fade)
    LyricsSwitchAnimationMode.SLIDE_UP -> getString(R.string.ui_slide_up)
    LyricsSwitchAnimationMode.SCALE_FADE -> getString(R.string.ui_scale)
}
