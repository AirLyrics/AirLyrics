package com.andsi.airlyrics.settings.store

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import com.andsi.airlyrics.core.model.FloatingLyricsPreset
import com.andsi.airlyrics.core.model.FloatingLyricsStyle
import com.andsi.airlyrics.core.prefs.PreferenceStore
import com.andsi.airlyrics.core.prefs.prefs as preferenceStore
import com.andsi.airlyrics.ui.theme.AirColorUtils

object FloatingLyricsStyleStore {
    private const val PREFS_NAME = "floating_lyrics_style"

    private const val KEY_PRESET = "preset"
    private const val KEY_TEXT_SIZE = "text_size"
    private const val KEY_TEXT_COLOR = "text_color"
    private const val KEY_KARAOKE_HIGHLIGHT_COLOR = "karaoke_highlight_color"
    private const val KEY_SHADOW_COLOR = "shadow_color"
    private const val KEY_SHADOW_RADIUS = "shadow_radius"
    private const val KEY_BACKGROUND_ENABLED = "background_enabled"
    private const val KEY_BACKGROUND_COLOR = "background_color"
    private const val KEY_BACKGROUND_ALPHA = "background_alpha"
    private const val KEY_CORNER_RADIUS = "corner_radius"
    private const val KEY_PADDING_HORIZONTAL = "padding_horizontal"
    private const val KEY_PADDING_VERTICAL = "padding_vertical"
    private const val KEY_MAX_WIDTH_PERCENT = "max_width_percent"
    private const val KEY_GRAVITY = "gravity"
    private const val KEY_LOCKED = "locked"
    private const val KEY_CLICK_THROUGH = "click_through"
    private const val KEY_POS_X = "pos_x"
    private const val KEY_POS_Y = "pos_y"
    private const val KEY_PREVIEW_EXPANDED = "preview_expanded"
    private const val KEY_AUTO_HIDE_WHEN_PAUSED = "auto_hide_when_paused"

    private const val DEFAULT_X = 100
    private const val DEFAULT_Y = 300

    const val PRESET_SUBTITLE = "subtitle"
    const val PRESET_BUBBLE = "bubble"
    const val DEFAULT_PRESET = PRESET_BUBBLE

    private data class PresetValues(
        val key: String,
        val textColor: Int = Color.WHITE,
        val karaokeHighlightColor: Int = Color.rgb(120, 220, 255),
        val shadowColor: Int = Color.BLACK,
        val shadowRadius: Float,
        val backgroundEnabled: Boolean,
        val backgroundColor: Int,
        val backgroundAlpha: Int,
        val cornerRadiusDp: Int,
        val paddingHorizontalDp: Int,
        val paddingVerticalDp: Int,
        val maxWidthPercent: Int,
        val gravity: Int = Gravity.CENTER
    )

    private val presetValues = listOf(
        PresetValues(
            key = PRESET_SUBTITLE,
            shadowRadius = 14f,
            backgroundEnabled = false,
            backgroundColor = Color.TRANSPARENT,
            backgroundAlpha = 0,
            cornerRadiusDp = 0,
            paddingHorizontalDp = 12,
            paddingVerticalDp = 8,
            maxWidthPercent = 92
        ),
        PresetValues(
            key = PRESET_BUBBLE,
            shadowRadius = 8f,
            backgroundEnabled = true,
            backgroundColor = Color.rgb(10, 14, 24),
            backgroundAlpha = 170,
            cornerRadiusDp = 20,
            paddingHorizontalDp = 18,
            paddingVerticalDp = 10,
            maxWidthPercent = 85
        )
    ).associateBy { it.key }

    val presets = listOf(
        FloatingLyricsPreset(PRESET_SUBTITLE, "Clean letters"),
        FloatingLyricsPreset(PRESET_BUBBLE, "Vinyl bubble")
    )

    private fun prefs(context: Context): PreferenceStore {
        return preferenceStore(context, PREFS_NAME)
    }

    private fun normalizePreset(key: String?): String {
        return if (key != null && presetValues.containsKey(key)) key else DEFAULT_PRESET
    }

    fun isPreviewExpanded(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_PREVIEW_EXPANDED, true)
    }

    fun setPreviewExpanded(context: Context, expanded: Boolean) {
        prefs(context).setBoolean(KEY_PREVIEW_EXPANDED, expanded)
    }

    fun getStyle(context: Context): FloatingLyricsStyle {
        val prefs = prefs(context)
        return FloatingLyricsStyle(
            presetName = normalizePreset(prefs.getString(KEY_PRESET, DEFAULT_PRESET)),
            textSizeSp = prefs.getFloat(KEY_TEXT_SIZE, 28f),
            textColor = prefs.getInt(KEY_TEXT_COLOR, Color.WHITE),
            karaokeHighlightColor = prefs.getInt(KEY_KARAOKE_HIGHLIGHT_COLOR, Color.rgb(120, 220, 255)),
            shadowColor = prefs.getInt(KEY_SHADOW_COLOR, Color.BLACK),
            shadowRadius = prefs.getFloat(KEY_SHADOW_RADIUS, 8f),
            backgroundEnabled = prefs.getBoolean(KEY_BACKGROUND_ENABLED, true),
            backgroundColor = prefs.getInt(KEY_BACKGROUND_COLOR, Color.rgb(10, 14, 24)),
            backgroundAlpha = prefs.getInt(KEY_BACKGROUND_ALPHA, 170),
            cornerRadiusDp = prefs.getInt(KEY_CORNER_RADIUS, 20),
            paddingHorizontalDp = prefs.getInt(KEY_PADDING_HORIZONTAL, 18),
            paddingVerticalDp = prefs.getInt(KEY_PADDING_VERTICAL, 10),
            maxWidthPercent = prefs.getInt(KEY_MAX_WIDTH_PERCENT, 85),
            gravity = prefs.getInt(KEY_GRAVITY, Gravity.CENTER)
        )
    }

    fun applyPreset(context: Context, preset: String) {
        val values = presetValues[normalizePreset(preset)] ?: return
        prefs(context).edit {
            putString(KEY_PRESET, values.key)
            putInt(KEY_TEXT_COLOR, values.textColor)
            putInt(KEY_KARAOKE_HIGHLIGHT_COLOR, values.karaokeHighlightColor)
            putInt(KEY_SHADOW_COLOR, values.shadowColor)
            putFloat(KEY_SHADOW_RADIUS, values.shadowRadius)
            putBoolean(KEY_BACKGROUND_ENABLED, values.backgroundEnabled)
            putInt(KEY_BACKGROUND_COLOR, values.backgroundColor)
            putInt(KEY_BACKGROUND_ALPHA, values.backgroundAlpha)
            putInt(KEY_CORNER_RADIUS, values.cornerRadiusDp)
            putInt(KEY_PADDING_HORIZONTAL, values.paddingHorizontalDp)
            putInt(KEY_PADDING_VERTICAL, values.paddingVerticalDp)
            putInt(KEY_MAX_WIDTH_PERCENT, values.maxWidthPercent)
            putInt(KEY_GRAVITY, values.gravity)
        }
    }

    fun setTextSize(context: Context, textSizeSp: Float) {
        prefs(context).setFloat(KEY_TEXT_SIZE, textSizeSp.coerceIn(14f, 56f))
    }

    fun setTextColor(context: Context, color: Int) {
        prefs(context).setInt(KEY_TEXT_COLOR, color)
    }

    fun setKaraokeHighlightColor(context: Context, color: Int) {
        prefs(context).setInt(KEY_KARAOKE_HIGHLIGHT_COLOR, color)
    }

    fun setBackgroundColor(context: Context, color: Int) {
        prefs(context).edit {
            putBoolean(KEY_BACKGROUND_ENABLED, Color.alpha(color) > 0)
            putInt(KEY_BACKGROUND_COLOR, AirColorUtils.opaqueRgb(color))
            putInt(KEY_BACKGROUND_ALPHA, Color.alpha(color))
        }
    }

    fun setBackgroundEnabled(context: Context, enabled: Boolean) {
        prefs(context).setBoolean(KEY_BACKGROUND_ENABLED, enabled)
    }

    fun setGravity(context: Context, gravity: Int) {
        prefs(context).setInt(KEY_GRAVITY, gravity)
    }

    fun setShadowColor(context: Context, color: Int) {
        prefs(context).setInt(KEY_SHADOW_COLOR, color)
    }

    fun setShadowRadius(context: Context, radius: Float) {
        prefs(context).setFloat(KEY_SHADOW_RADIUS, radius.coerceIn(0f, 24f))
    }

    fun setCornerRadius(context: Context, radiusDp: Int) {
        prefs(context).setInt(KEY_CORNER_RADIUS, radiusDp.coerceIn(0, 36))
    }

    fun setPaddingHorizontal(context: Context, paddingDp: Int) {
        prefs(context).setInt(KEY_PADDING_HORIZONTAL, paddingDp.coerceIn(0, 36))
    }

    fun setPaddingVertical(context: Context, paddingDp: Int) {
        prefs(context).setInt(KEY_PADDING_VERTICAL, paddingDp.coerceIn(0, 28))
    }

    fun setMaxWidthPercent(context: Context, percent: Int) {
        prefs(context).setInt(KEY_MAX_WIDTH_PERCENT, percent.coerceIn(45, 100))
    }

    fun setLocked(context: Context, locked: Boolean) {
        prefs(context).setBoolean(KEY_LOCKED, locked)
    }

    fun isLocked(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_LOCKED, false)
    }

    fun setClickThrough(context: Context, clickThrough: Boolean) {
        prefs(context).setBoolean(KEY_CLICK_THROUGH, clickThrough)
    }

    fun isClickThrough(context: Context): Boolean {
        val prefs = prefs(context)
        return prefs.getBoolean(KEY_CLICK_THROUGH, prefs.getBoolean(KEY_LOCKED, false))
    }

    fun isClickThroughFollowingLocked(context: Context): Boolean {
        return !prefs(context).contains(KEY_CLICK_THROUGH)
    }

    fun setAutoHideWhenPaused(context: Context, enabled: Boolean) {
        prefs(context).setBoolean(KEY_AUTO_HIDE_WHEN_PAUSED, enabled)
    }

    fun isAutoHideWhenPaused(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTO_HIDE_WHEN_PAUSED, false)
    }

    fun savePosition(context: Context, x: Int, y: Int) {
        prefs(context).edit {
            putInt(KEY_POS_X, x)
            putInt(KEY_POS_Y, y)
        }
    }

    fun getPosition(context: Context): Pair<Int, Int> {
        val prefs = prefs(context)
        return prefs.getInt(KEY_POS_X, DEFAULT_X) to prefs.getInt(KEY_POS_Y, DEFAULT_Y)
    }

}
