package com.andsi.airlyrics.ui.pages

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.TextView
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.i18n.tr
import com.andsi.airlyrics.settings.model.LyricsContentDisplayMode
import com.andsi.airlyrics.settings.model.LyricsLineDisplayMode
import com.andsi.airlyrics.settings.model.LyricsSwitchAnimationMode
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.ui.theme.colorTextStrong

internal fun MainActivity.floatingSectionTitle(title: CharSequence): TextView {
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
        "subtitle" -> tr("纯净字母", "Clean letters")
        "bubble" -> tr("黑胶气泡", "Vinyl bubble")
        else -> localizedFloatingPresetTitle(FloatingLyricsStyleStore.DEFAULT_PRESET)
    }
}

internal fun Context.localizedFloatingGravityTitle(gravity: Int): String {
    return when (gravity) {
        Gravity.START or Gravity.CENTER_VERTICAL -> tr("左对齐", "Left")
        Gravity.END or Gravity.CENTER_VERTICAL -> tr("右对齐", "Right")
        else -> tr("居中", "Center")
    }
}

internal fun Context.localizedContentDisplayTitle(mode: LyricsContentDisplayMode): String {
    return when (mode) {
        LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION -> tr("原文 + 翻译", "Original + translation")
        LyricsContentDisplayMode.ORIGINAL_ONLY -> tr("仅原文", "Original only")
        LyricsContentDisplayMode.TRANSLATION_ONLY -> tr("仅翻译", "Translation only")
    }
}

internal fun Context.localizedLineDisplayTitle(mode: LyricsLineDisplayMode): String {
    return when (mode) {
        LyricsLineDisplayMode.CURRENT_ONLY -> tr("当前句", "Current line")
        LyricsLineDisplayMode.PREVIOUS_AND_CURRENT -> tr("上一句 + 当前句", "Previous + current")
        LyricsLineDisplayMode.CURRENT_AND_NEXT -> tr("当前句 + 下一句", "Current + next")
        LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> tr("上 + 当前 + 下", "Prev + current + next")
    }
}

internal fun Context.localizedSwitchAnimationTitle(mode: LyricsSwitchAnimationMode): String {
    return when (mode) {
        LyricsSwitchAnimationMode.NONE -> tr("关闭", "Off")
        LyricsSwitchAnimationMode.FADE -> tr("柔和淡入", "Soft fade")
        LyricsSwitchAnimationMode.SLIDE_UP -> tr("上滑淡入", "Slide up")
        LyricsSwitchAnimationMode.SCALE_FADE -> tr("轻微缩放", "Scale fade")
    }
}

internal fun Context.localizedFloatingModeTitle(title: String): String {
    return when (title) {
        LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION.title -> localizedContentDisplayTitle(LyricsContentDisplayMode.ORIGINAL_WITH_TRANSLATION)
        LyricsContentDisplayMode.ORIGINAL_ONLY.title -> localizedContentDisplayTitle(LyricsContentDisplayMode.ORIGINAL_ONLY)
        LyricsContentDisplayMode.TRANSLATION_ONLY.title -> localizedContentDisplayTitle(LyricsContentDisplayMode.TRANSLATION_ONLY)
        LyricsLineDisplayMode.CURRENT_ONLY.title -> localizedLineDisplayTitle(LyricsLineDisplayMode.CURRENT_ONLY)
        LyricsLineDisplayMode.PREVIOUS_AND_CURRENT.title -> localizedLineDisplayTitle(LyricsLineDisplayMode.PREVIOUS_AND_CURRENT)
        LyricsLineDisplayMode.CURRENT_AND_NEXT.title -> localizedLineDisplayTitle(LyricsLineDisplayMode.CURRENT_AND_NEXT)
        LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT.title -> localizedLineDisplayTitle(LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT)
        LyricsSwitchAnimationMode.NONE.title -> localizedSwitchAnimationTitle(LyricsSwitchAnimationMode.NONE)
        LyricsSwitchAnimationMode.FADE.title -> localizedSwitchAnimationTitle(LyricsSwitchAnimationMode.FADE)
        LyricsSwitchAnimationMode.SLIDE_UP.title -> localizedSwitchAnimationTitle(LyricsSwitchAnimationMode.SLIDE_UP)
        LyricsSwitchAnimationMode.SCALE_FADE.title -> localizedSwitchAnimationTitle(LyricsSwitchAnimationMode.SCALE_FADE)
        else -> title
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
