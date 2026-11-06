package com.andsi.airlyrics.ui.state

import android.os.Bundle
import com.andsi.airlyrics.core.model.FloatingLyricsStyle
import com.andsi.airlyrics.core.model.FloatingLyricsFontFamily

internal fun FloatingLyricsStyle.undoChangesTo(next: FloatingLyricsStyle): Bundle = Bundle().apply {
    if (presetName != next.presetName) putString("presetName", presetName)
    if (textSizeSp != next.textSizeSp) putFloat("textSizeSp", textSizeSp)
    if (textColor != next.textColor) putInt("textColor", textColor)
    if (wordByWordHighlightColor != next.wordByWordHighlightColor) putInt("wordByWordHighlightColor", wordByWordHighlightColor)
    if (shadowColor != next.shadowColor) putInt("shadowColor", shadowColor)
    if (shadowRadius != next.shadowRadius) putFloat("shadowRadius", shadowRadius)
    if (backgroundEnabled != next.backgroundEnabled) putBoolean("backgroundEnabled", backgroundEnabled)
    if (backgroundColor != next.backgroundColor) putInt("backgroundColor", backgroundColor)
    if (backgroundAlpha != next.backgroundAlpha) putInt("backgroundAlpha", backgroundAlpha)
    if (cornerRadiusDp != next.cornerRadiusDp) putInt("cornerRadiusDp", cornerRadiusDp)
    if (paddingHorizontalDp != next.paddingHorizontalDp) putInt("paddingHorizontalDp", paddingHorizontalDp)
    if (paddingVerticalDp != next.paddingVerticalDp) putInt("paddingVerticalDp", paddingVerticalDp)
    if (maxWidthPercent != next.maxWidthPercent) putInt("maxWidthPercent", maxWidthPercent)
    if (gravity != next.gravity) putInt("gravity", gravity)
    if (fontFamily != next.fontFamily) putString("fontFamily", fontFamily.key)
    if (fontWeight != next.fontWeight) putInt("fontWeight", fontWeight)
    if (glow.enabled != next.glow.enabled) putBoolean("glowEnabled", glow.enabled)
    if (glow.strengthPercent != next.glow.strengthPercent) putInt("glowStrengthPercent", glow.strengthPercent)
    if (glow.spreadPercent != next.glow.spreadPercent) putInt("glowSpreadPercent", glow.spreadPercent)
}

internal fun FloatingLyricsStyle.restoreFields(snapshot: Bundle): FloatingLyricsStyle = copy(
    presetName = if (snapshot.containsKey("presetName")) snapshot.getString("presetName") ?: presetName else presetName,
    textSizeSp = if (snapshot.containsKey("textSizeSp")) snapshot.getFloat("textSizeSp") else textSizeSp,
    textColor = if (snapshot.containsKey("textColor")) snapshot.getInt("textColor") else textColor,
    wordByWordHighlightColor = if (snapshot.containsKey("wordByWordHighlightColor")) snapshot.getInt("wordByWordHighlightColor") else wordByWordHighlightColor,
    shadowColor = if (snapshot.containsKey("shadowColor")) snapshot.getInt("shadowColor") else shadowColor,
    shadowRadius = if (snapshot.containsKey("shadowRadius")) snapshot.getFloat("shadowRadius") else shadowRadius,
    backgroundEnabled = if (snapshot.containsKey("backgroundEnabled")) snapshot.getBoolean("backgroundEnabled") else backgroundEnabled,
    backgroundColor = if (snapshot.containsKey("backgroundColor")) snapshot.getInt("backgroundColor") else backgroundColor,
    backgroundAlpha = if (snapshot.containsKey("backgroundAlpha")) snapshot.getInt("backgroundAlpha") else backgroundAlpha,
    cornerRadiusDp = if (snapshot.containsKey("cornerRadiusDp")) snapshot.getInt("cornerRadiusDp") else cornerRadiusDp,
    paddingHorizontalDp = if (snapshot.containsKey("paddingHorizontalDp")) snapshot.getInt("paddingHorizontalDp") else paddingHorizontalDp,
    paddingVerticalDp = if (snapshot.containsKey("paddingVerticalDp")) snapshot.getInt("paddingVerticalDp") else paddingVerticalDp,
    maxWidthPercent = if (snapshot.containsKey("maxWidthPercent")) snapshot.getInt("maxWidthPercent") else maxWidthPercent,
    gravity = if (snapshot.containsKey("gravity")) snapshot.getInt("gravity") else gravity,
    fontFamily = if (snapshot.containsKey("fontFamily")) FloatingLyricsFontFamily.fromKey(snapshot.getString("fontFamily")) else fontFamily,
    fontWeight = if (snapshot.containsKey("fontWeight")) snapshot.getInt("fontWeight") else fontWeight,
    glow = glow.copy(
        enabled = if (snapshot.containsKey("glowEnabled")) snapshot.getBoolean("glowEnabled") else glow.enabled,
        strengthPercent = if (snapshot.containsKey("glowStrengthPercent")) snapshot.getInt("glowStrengthPercent") else glow.strengthPercent,
        spreadPercent = if (snapshot.containsKey("glowSpreadPercent")) snapshot.getInt("glowSpreadPercent") else glow.spreadPercent
    ).normalized()
)
