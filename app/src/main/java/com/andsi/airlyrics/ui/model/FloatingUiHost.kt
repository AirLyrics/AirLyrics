package com.andsi.airlyrics.ui.model

import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.settings.model.FloatingLyricsStyle

internal interface FloatingUiHost {
    fun settingGrid(vararg items: FloatingSettingTile): LinearLayout
    fun floatingTile(item: FloatingSettingTile): LinearLayout
    fun floatingFocusBubble(
        title: String,
        subtitle: String,
        onClose: () -> Unit,
        content: LinearLayout.() -> Unit
    ): LinearLayout

    fun showFloatingSettingPanel(
        title: String,
        subtitle: String,
        content: LinearLayout.() -> Unit
    )

    fun floatingPreviewSummary(style: FloatingLyricsStyle): String
    fun floatingDisplaySummary(): String
    fun floatingLockButtonText(): String
    fun floatingClickThroughButtonText(): String
    fun floatingPreviewText(text: CharSequence, style: FloatingLyricsStyle): TextView
    fun TextView.applyFloatingPreviewStyle(style: FloatingLyricsStyle)
    fun applyFloatingPreset(preset: String)
    fun applyFloatingTextSize(textSizeSp: Float, refreshPage: Boolean = true)
    fun applyFloatingTextColor(color: Int, refreshPage: Boolean = true)
    fun applyFloatingBackgroundColor(color: Int, refreshPage: Boolean = true)
    fun applyFloatingGravity(gravity: Int)
    fun notifyFloatingStyleChanged()
}
