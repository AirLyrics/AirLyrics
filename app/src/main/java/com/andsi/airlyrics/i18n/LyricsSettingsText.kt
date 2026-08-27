package com.andsi.airlyrics.i18n

import android.content.Context
import androidx.annotation.StringRes
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode

internal fun Context.localizedPlainLyricsSourceTitle(
    plainLyricsSearchSource: PlainLyricsSearchSource
): String = getString(plainLyricsSourceTitleRes(plainLyricsSearchSource))

@StringRes
internal fun plainLyricsSourceTitleRes(plainLyricsSearchSource: PlainLyricsSearchSource): Int =
    when (plainLyricsSearchSource) {
        PlainLyricsSearchSource.LOCAL_ONLY -> R.string.ui_local_only
        PlainLyricsSearchSource.NETEASE -> R.string.ui_netease_cloud_music
        PlainLyricsSearchSource.MUSIXMATCH -> R.string.provider_musixmatch
    }

internal fun Context.localizedLyricsContentModeTitle(mode: LyricsContentDisplayMode): String =
    getString(lyricsContentModeTitleRes(mode))

@StringRes
internal fun lyricsContentModeTitleRes(mode: LyricsContentDisplayMode): Int = when (mode) {
    LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION -> R.string.ui_original_translation
    LyricsContentDisplayMode.ORIGINAL_ONLY -> R.string.ui_original_only
    LyricsContentDisplayMode.TRANSLATION_ONLY -> R.string.ui_translation_only
}

internal fun Context.localizedLyricsLineModeTitle(mode: LyricsLineDisplayMode): String =
    getString(lyricsLineModeTitleRes(mode))

@StringRes
internal fun lyricsLineModeTitleRes(mode: LyricsLineDisplayMode): Int = when (mode) {
    LyricsLineDisplayMode.CURRENT_ONLY -> R.string.ui_current_line
    LyricsLineDisplayMode.PREVIOUS_AND_CURRENT -> R.string.ui_previous_current
    LyricsLineDisplayMode.CURRENT_AND_NEXT -> R.string.ui_current_next
    LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> R.string.ui_prev_current_next
}

internal fun Context.localizedLyricsSwitchAnimationTitle(mode: LyricsSwitchAnimationMode): String =
    getString(lyricsSwitchAnimationTitleRes(mode))

@StringRes
internal fun lyricsSwitchAnimationTitleRes(mode: LyricsSwitchAnimationMode): Int = when (mode) {
    LyricsSwitchAnimationMode.NONE -> R.string.ui_off
    LyricsSwitchAnimationMode.FADE -> R.string.ui_soft_fade
    LyricsSwitchAnimationMode.SLIDE_UP -> R.string.ui_slide_up
    LyricsSwitchAnimationMode.SCALE_FADE -> R.string.ui_scale_fade
}
