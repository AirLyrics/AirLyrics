package com.andsi.airlyrics.core.settings.model

/**
 * Visual configuration for the floating lyrics window.
 *
 * Keep this file data-only so UI pages and the floating window controller can
 * share the same contract without depending on each other's implementation.
 */
data class FloatingLyricsStyle(
    val presetName: String,
    val textSizeSp: Float,
    val textColor: Int,
    val shadowColor: Int,
    val shadowRadius: Float,
    val backgroundEnabled: Boolean,
    val backgroundColor: Int,
    val backgroundAlpha: Int,
    val cornerRadiusDp: Int,
    val paddingHorizontalDp: Int,
    val paddingVerticalDp: Int,
    val maxWidthPercent: Int,
    val gravity: Int
)

/** A named preset shown in the floating-window settings page. */
data class FloatingLyricsPreset(
    val key: String,
    val title: String
)
