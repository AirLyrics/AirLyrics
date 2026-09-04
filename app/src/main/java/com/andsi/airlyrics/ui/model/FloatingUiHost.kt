package com.andsi.airlyrics.ui.model

import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.core.model.FloatingLyricsPreset
import com.andsi.airlyrics.core.model.FloatingLyricsStyle
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode

internal data class FloatingFocusBubbleHandle(
    val view: LinearLayout,
    val rebuildContent: () -> Unit,
    val updateResetAction: (Boolean, Boolean) -> Unit
)

internal interface FloatingUiHost {
    fun settingGrid(vararg items: FloatingSettingTile): LinearLayout
    fun floatingTile(item: FloatingSettingTile): LinearLayout
    fun floatingFocusBubble(
        title: String,
        subtitle: String,
        onReset: (() -> Unit)?,
        onClose: () -> Unit,
        content: LinearLayout.() -> Unit
    ): FloatingFocusBubbleHandle

    fun floatingStyle(): FloatingLyricsStyle
    fun floatingStyleDefaults(preset: String): FloatingLyricsStyle
    fun floatingPresets(): List<FloatingLyricsPreset>
    fun floatingCustomFontName(): String?
    fun hasFloatingCustomFont(): Boolean
    fun selectFloatingFontFile()
    fun isFloatingPreviewExpanded(): Boolean
    fun setFloatingPreviewExpanded(expanded: Boolean)
    fun floatingDisplaySummary(): String
    fun floatingLockButtonText(): String
    fun floatingClickThroughButtonText(): String
    fun autoHideWhenPausedEnabled(): Boolean
    fun displayScopeSupported(): Boolean
    fun displayScopeEnabled(): Boolean
    fun displayScopeSelectedCount(): Int
    fun displayScopeSummary(): String
    fun hasUsageStatsAccess(): Boolean
    fun floatingPreviewText(text: CharSequence, style: FloatingLyricsStyle): TextView
    fun TextView.applyFloatingPreviewStyle(style: FloatingLyricsStyle)
    fun applyFloatingPreset(preset: String)
    fun applyFloatingStyle(style: FloatingLyricsStyle)
    fun applyFloatingTextSize(textSizeSp: Float, refreshPage: Boolean = true)
    fun applyFloatingTextColor(color: Int, refreshPage: Boolean = true)
    fun applyFloatingTextAlpha(alpha: Int, refreshPage: Boolean = true)
    fun applyFloatingBackgroundColor(color: Int, refreshPage: Boolean = true)
    fun applyFloatingBackgroundEnabled(enabled: Boolean)
    fun applyFloatingBackgroundAlpha(alpha: Int)
    fun applyFloatingGravity(gravity: Int)
    fun applyFloatingShadowRadius(radius: Float)
    fun applyFloatingShadowColor(color: Int)
    fun applyFloatingMaxWidthPercent(percent: Int)
    fun applyFloatingPaddingHorizontal(paddingDp: Int)
    fun applyFloatingPaddingVertical(paddingDp: Int)
    fun applyFloatingCornerRadius(radiusDp: Int)
    fun applyFloatingWordByWordHighlightColor(color: Int)
    fun lyricsContentDisplayMode(): LyricsContentDisplayMode
    fun lyricsLineDisplayMode(): LyricsLineDisplayMode
    fun lyricsSwitchAnimationMode(): LyricsSwitchAnimationMode
    fun wordByWordLyricsEnabled(): Boolean
    fun setLyricsContentDisplayMode(mode: LyricsContentDisplayMode)
    fun setLyricsLineDisplayMode(mode: LyricsLineDisplayMode)
    fun setLyricsSwitchAnimationMode(mode: LyricsSwitchAnimationMode)
    fun setWordByWordLyricsEnabled(enabled: Boolean)
    fun notifyFloatingStyleChanged()
}
