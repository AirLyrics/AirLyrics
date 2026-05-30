package com.andsi.airlyrics.settings.store

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import com.andsi.airlyrics.settings.model.FloatingLyricsPreset
import com.andsi.airlyrics.settings.model.FloatingLyricsStyle

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

    private const val DEFAULT_X = 100
    private const val DEFAULT_Y = 300

    val presets = listOf(
        FloatingLyricsPreset("subtitle", "纯净字母"),
        FloatingLyricsPreset("bubble", "黑胶气泡")
    )


    fun isPreviewExpanded(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREVIEW_EXPANDED, true)
    }

    fun setPreviewExpanded(context: Context, expanded: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PREVIEW_EXPANDED, expanded)
            .apply()
    }

    fun getStyle(context: Context): FloatingLyricsStyle {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return FloatingLyricsStyle(
            presetName = prefs.getString(KEY_PRESET, "bubble") ?: "bubble",
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
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        when (preset) {
            "transparent" -> editor
                .putString(KEY_PRESET, preset)
                .putInt(KEY_TEXT_COLOR, Color.WHITE)
                .putInt(KEY_KARAOKE_HIGHLIGHT_COLOR, Color.rgb(120, 220, 255))
                .putInt(KEY_SHADOW_COLOR, Color.BLACK)
                .putFloat(KEY_SHADOW_RADIUS, 8f)
                .putBoolean(KEY_BACKGROUND_ENABLED, false)
                .putInt(KEY_BACKGROUND_COLOR, Color.TRANSPARENT)
                .putInt(KEY_BACKGROUND_ALPHA, 0)
                .putInt(KEY_CORNER_RADIUS, 0)
                .putInt(KEY_PADDING_HORIZONTAL, 16)
                .putInt(KEY_PADDING_VERTICAL, 8)
                .putInt(KEY_MAX_WIDTH_PERCENT, 88)
                .putInt(KEY_GRAVITY, Gravity.CENTER)

            "neon" -> editor
                .putString(KEY_PRESET, preset)
                .putInt(KEY_TEXT_COLOR, Color.rgb(176, 226, 255))
                .putInt(KEY_KARAOKE_HIGHLIGHT_COLOR, Color.rgb(255, 235, 120))
                .putInt(KEY_SHADOW_COLOR, Color.rgb(66, 170, 255))
                .putFloat(KEY_SHADOW_RADIUS, 12f)
                .putBoolean(KEY_BACKGROUND_ENABLED, true)
                .putInt(KEY_BACKGROUND_COLOR, Color.rgb(8, 14, 30))
                .putInt(KEY_BACKGROUND_ALPHA, 155)
                .putInt(KEY_CORNER_RADIUS, 22)
                .putInt(KEY_PADDING_HORIZONTAL, 20)
                .putInt(KEY_PADDING_VERTICAL, 10)
                .putInt(KEY_MAX_WIDTH_PERCENT, 86)
                .putInt(KEY_GRAVITY, Gravity.CENTER)

            "subtitle" -> editor
                .putString(KEY_PRESET, preset)
                .putInt(KEY_TEXT_COLOR, Color.WHITE)
                .putInt(KEY_KARAOKE_HIGHLIGHT_COLOR, Color.rgb(120, 220, 255))
                .putInt(KEY_SHADOW_COLOR, Color.BLACK)
                .putFloat(KEY_SHADOW_RADIUS, 14f)
                .putBoolean(KEY_BACKGROUND_ENABLED, false)
                .putInt(KEY_BACKGROUND_COLOR, Color.TRANSPARENT)
                .putInt(KEY_BACKGROUND_ALPHA, 0)
                .putInt(KEY_CORNER_RADIUS, 0)
                .putInt(KEY_PADDING_HORIZONTAL, 12)
                .putInt(KEY_PADDING_VERTICAL, 8)
                .putInt(KEY_MAX_WIDTH_PERCENT, 92)
                .putInt(KEY_GRAVITY, Gravity.CENTER)

            else -> editor
                .putString(KEY_PRESET, "bubble")
                .putInt(KEY_TEXT_COLOR, Color.WHITE)
                .putInt(KEY_KARAOKE_HIGHLIGHT_COLOR, Color.rgb(120, 220, 255))
                .putInt(KEY_SHADOW_COLOR, Color.BLACK)
                .putFloat(KEY_SHADOW_RADIUS, 8f)
                .putBoolean(KEY_BACKGROUND_ENABLED, true)
                .putInt(KEY_BACKGROUND_COLOR, Color.rgb(10, 14, 24))
                .putInt(KEY_BACKGROUND_ALPHA, 170)
                .putInt(KEY_CORNER_RADIUS, 20)
                .putInt(KEY_PADDING_HORIZONTAL, 18)
                .putInt(KEY_PADDING_VERTICAL, 10)
                .putInt(KEY_MAX_WIDTH_PERCENT, 85)
                .putInt(KEY_GRAVITY, Gravity.CENTER)
        }
        editor.apply()
    }

    fun setTextSize(context: Context, textSizeSp: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_TEXT_SIZE, textSizeSp.coerceIn(14f, 56f))
            .apply()
    }

    fun setTextColor(context: Context, color: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_TEXT_COLOR, color)
            .apply()
    }

    fun setKaraokeHighlightColor(context: Context, color: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_KARAOKE_HIGHLIGHT_COLOR, color)
            .apply()
    }

    fun setBackgroundColor(context: Context, color: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BACKGROUND_ENABLED, Color.alpha(color) > 0)
            .putInt(KEY_BACKGROUND_COLOR, Color.rgb(Color.red(color), Color.green(color), Color.blue(color)))
            .putInt(KEY_BACKGROUND_ALPHA, Color.alpha(color))
            .apply()
    }

    fun setBackgroundEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BACKGROUND_ENABLED, enabled)
            .apply()
    }

    fun setGravity(context: Context, gravity: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_GRAVITY, gravity)
            .apply()
    }

    fun setShadowColor(context: Context, color: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SHADOW_COLOR, color)
            .apply()
    }

    fun setShadowRadius(context: Context, radius: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_SHADOW_RADIUS, radius.coerceIn(0f, 24f))
            .apply()
    }

    fun setCornerRadius(context: Context, radiusDp: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CORNER_RADIUS, radiusDp.coerceIn(0, 36))
            .apply()
    }

    fun setPaddingHorizontal(context: Context, paddingDp: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PADDING_HORIZONTAL, paddingDp.coerceIn(0, 36))
            .apply()
    }

    fun setPaddingVertical(context: Context, paddingDp: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PADDING_VERTICAL, paddingDp.coerceIn(0, 28))
            .apply()
    }

    fun setMaxWidthPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MAX_WIDTH_PERCENT, percent.coerceIn(45, 100))
            .apply()
    }

    fun setLocked(context: Context, locked: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LOCKED, locked)
            .apply()
    }

    fun isLocked(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOCKED, false)
    }

    fun setClickThrough(context: Context, clickThrough: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CLICK_THROUGH, clickThrough)
            .apply()
    }

    fun isClickThrough(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_CLICK_THROUGH, prefs.getBoolean(KEY_LOCKED, false))
    }

    fun savePosition(context: Context, x: Int, y: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_POS_X, x)
            .putInt(KEY_POS_Y, y)
            .apply()
    }

    fun getPosition(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_POS_X, DEFAULT_X) to prefs.getInt(KEY_POS_Y, DEFAULT_Y)
    }

    fun getPresetTitle(key: String): String {
        return presets.firstOrNull { it.key == key }?.title ?: "黑胶气泡"
    }

    fun colorSummary(color: Int): String {
        return "R${Color.red(color)} G${Color.green(color)} B${Color.blue(color)} A${Color.alpha(color)}"
    }

    fun backgroundColorWithAlpha(style: FloatingLyricsStyle): Int {
        return Color.argb(
            style.backgroundAlpha.coerceIn(0, 255),
            Color.red(style.backgroundColor),
            Color.green(style.backgroundColor),
            Color.blue(style.backgroundColor)
        )
    }

    fun getGravityTitle(gravity: Int): String {
        return when (gravity) {
            Gravity.START or Gravity.CENTER_VERTICAL -> "左对齐"
            Gravity.END or Gravity.CENTER_VERTICAL -> "右对齐"
            else -> "居中"
        }
    }
}
