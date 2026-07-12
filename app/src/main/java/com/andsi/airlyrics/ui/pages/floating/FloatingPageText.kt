package com.andsi.airlyrics.ui.pages.floating

import com.andsi.airlyrics.R

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.TextView
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.ui.theme.colorTextStrong

internal fun MainUiHost.floatingSectionTitle(title: CharSequence): TextView {
    return TextView(this).apply {
        text = title
        textSize = FloatingPageTokens.SECTION_TITLE_TEXT_SP
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(colorTextStrong)
        setPadding(
            0,
            dp(FloatingPageTokens.SECTION_TITLE_PADDING_TOP_DP),
            0,
            dp(FloatingPageTokens.SECTION_TITLE_PADDING_BOTTOM_DP)
        )
    }
}

internal fun Context.localizedFloatingPresetTitle(key: String): String {
    return when (key) {
        "subtitle" -> getString(R.string.ui_clean_letters)
        "bubble" -> getString(R.string.ui_vinyl_bubble)
        else -> localizedFloatingPresetTitle(FloatingLyricsStyleStore.DEFAULT_PRESET)
    }
}

internal fun Context.localizedFloatingGravityTitle(gravity: Int): String {
    return when (gravity) {
        Gravity.START or Gravity.CENTER_VERTICAL -> getString(R.string.ui_left)
        Gravity.END or Gravity.CENTER_VERTICAL -> getString(R.string.ui_right)
        else -> getString(R.string.ui_center)
    }
}

internal fun previewMaxLines(mode: LyricsLineDisplayMode): Int {
    return when (mode) {
        LyricsLineDisplayMode.CURRENT_ONLY -> 2
        LyricsLineDisplayMode.PREVIOUS_AND_CURRENT,
        LyricsLineDisplayMode.CURRENT_AND_NEXT -> 4
        LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> 6
    }
}

internal fun previewLineText(
    mode: LyricsContentDisplayMode,
    original: String,
    translation: String
): String {
    return when (mode) {
        LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION -> "$original\n$translation"
        LyricsContentDisplayMode.ORIGINAL_ONLY -> original
        LyricsContentDisplayMode.TRANSLATION_ONLY -> translation
    }
}
