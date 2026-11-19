package com.andsi.airlyrics.ui.state

import android.graphics.Color
import android.os.Bundle
import com.andsi.airlyrics.core.color.AirColorUtils
import com.andsi.airlyrics.core.model.FloatingLyricsFontFamily
import com.andsi.airlyrics.core.model.FloatingLyricsStyle

internal fun FloatingLyricsStyle.undoChangesTo(next: FloatingLyricsStyle): Bundle = Bundle().apply {
    if (presetName != next.presetName) putString("presetName", presetName)
    if (textSizeSp != next.textSizeSp) putFloat("textSizeSp", textSizeSp)
    putColorChanges("textColor", textColor, next.textColor)
    if (wordByWordHighlightColor != next.wordByWordHighlightColor) putInt("wordByWordHighlightColor", wordByWordHighlightColor)
    putColorChanges("shadowColor", shadowColor, next.shadowColor)
    if (shadowRadius != next.shadowRadius) putFloat("shadowRadius", shadowRadius)
    if (backgroundEnabled != next.backgroundEnabled) putBoolean("backgroundEnabled", backgroundEnabled)
    if (backgroundColor != next.backgroundColor) putInt("backgroundColor", backgroundColor)
    if (backgroundAlpha != next.backgroundAlpha) putInt("backgroundAlpha", backgroundAlpha)
    if (backgroundBlurPercent != next.backgroundBlurPercent) putInt("backgroundBlurPercent", backgroundBlurPercent)
    if (cornerRadiusDp != next.cornerRadiusDp) putInt("cornerRadiusDp", cornerRadiusDp)
    if (paddingHorizontalDp != next.paddingHorizontalDp) putInt("paddingHorizontalDp", paddingHorizontalDp)
    if (paddingVerticalDp != next.paddingVerticalDp) putInt("paddingVerticalDp", paddingVerticalDp)
    if (maxWidthPercent != next.maxWidthPercent) putInt("maxWidthPercent", maxWidthPercent)
    if (gravity != next.gravity) putInt("gravity", gravity)
    if (fontFamily != next.fontFamily || customFontId != next.customFontId) {
        putString("fontFamily", fontFamily.key)
        putString("customFontId", customFontId)
    }
    if (fontWeight != next.fontWeight) putInt("fontWeight", fontWeight)
    if (glow.enabled != next.glow.enabled) putBoolean("glowEnabled", glow.enabled)
    if (glow.strengthPercent != next.glow.strengthPercent) putInt("glowStrengthPercent", glow.strengthPercent)
    if (glow.spreadPercent != next.glow.spreadPercent) putInt("glowSpreadPercent", glow.spreadPercent)
}

internal fun FloatingLyricsStyle.restoreFields(snapshot: Bundle): FloatingLyricsStyle = copy(
    presetName = if (snapshot.containsKey("presetName")) snapshot.getString("presetName") ?: presetName else presetName,
    textSizeSp = if (snapshot.containsKey("textSizeSp")) snapshot.getFloat("textSizeSp") else textSizeSp,
    textColor = snapshot.restoreColor("textColor", textColor),
    wordByWordHighlightColor = if (snapshot.containsKey("wordByWordHighlightColor")) snapshot.getInt("wordByWordHighlightColor") else wordByWordHighlightColor,
    shadowColor = snapshot.restoreColor("shadowColor", shadowColor),
    shadowRadius = if (snapshot.containsKey("shadowRadius")) snapshot.getFloat("shadowRadius") else shadowRadius,
    backgroundEnabled = if (snapshot.containsKey("backgroundEnabled")) snapshot.getBoolean("backgroundEnabled") else backgroundEnabled,
    backgroundColor = if (snapshot.containsKey("backgroundColor")) snapshot.getInt("backgroundColor") else backgroundColor,
    backgroundAlpha = if (snapshot.containsKey("backgroundAlpha")) snapshot.getInt("backgroundAlpha") else backgroundAlpha,
    backgroundBlurPercent = if (snapshot.containsKey("backgroundBlurPercent")) snapshot.getInt("backgroundBlurPercent") else backgroundBlurPercent,
    cornerRadiusDp = if (snapshot.containsKey("cornerRadiusDp")) snapshot.getInt("cornerRadiusDp") else cornerRadiusDp,
    paddingHorizontalDp = if (snapshot.containsKey("paddingHorizontalDp")) snapshot.getInt("paddingHorizontalDp") else paddingHorizontalDp,
    paddingVerticalDp = if (snapshot.containsKey("paddingVerticalDp")) snapshot.getInt("paddingVerticalDp") else paddingVerticalDp,
    maxWidthPercent = if (snapshot.containsKey("maxWidthPercent")) snapshot.getInt("maxWidthPercent") else maxWidthPercent,
    gravity = if (snapshot.containsKey("gravity")) snapshot.getInt("gravity") else gravity,
    fontFamily = if (snapshot.containsKey("fontFamily")) FloatingLyricsFontFamily.fromKey(snapshot.getString("fontFamily")) else fontFamily,
    customFontId = if (snapshot.containsKey("customFontId")) snapshot.getString("customFontId") else customFontId,
    fontWeight = if (snapshot.containsKey("fontWeight")) snapshot.getInt("fontWeight") else fontWeight,
    glow = glow.copy(
        enabled = if (snapshot.containsKey("glowEnabled")) snapshot.getBoolean("glowEnabled") else glow.enabled,
        strengthPercent = if (snapshot.containsKey("glowStrengthPercent")) snapshot.getInt("glowStrengthPercent") else glow.strengthPercent,
        spreadPercent = if (snapshot.containsKey("glowSpreadPercent")) snapshot.getInt("glowSpreadPercent") else glow.spreadPercent
    ).normalized()
)

// Color and opacity are separate controls even when stored as one ARGB value.
private fun Bundle.putColorChanges(key: String, previous: Int, next: Int) {
    val rgb = AirColorUtils.opaqueRgb(previous)
    if (rgb != AirColorUtils.opaqueRgb(next)) putInt("${key}Rgb", rgb)
    val alpha = Color.alpha(previous)
    if (alpha != Color.alpha(next)) putInt("${key}Alpha", alpha)
}

private fun Bundle.restoreColor(key: String, current: Int): Int {
    val base = getInt(key, current) // Full ARGB snapshots saved before the controls were split.
    return AirColorUtils.withAlpha(getInt("${key}Rgb", base), getInt("${key}Alpha", Color.alpha(base)))
}
