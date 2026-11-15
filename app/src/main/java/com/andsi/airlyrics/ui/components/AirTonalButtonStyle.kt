package com.andsi.airlyrics.ui.components

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.core.model.ButtonStyle
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.AirLyricsPalette
import com.andsi.airlyrics.ui.theme.AirLyricsTheme
import com.andsi.airlyrics.ui.theme.airLyricsPalette
import com.andsi.airlyrics.ui.theme.colorOnAccent
import com.andsi.airlyrics.ui.theme.colorTextStrong
import com.andsi.airlyrics.ui.theme.bindAppearance

internal enum class AirTonalButtonTone {
    PRIMARY,
    DEFAULT,
    SELECTED,
    SOURCE_SELECTED,
    SUCCESS,
    DANGER
}

internal data class AirTonalButtonStyle(
    val fillColor: Int,
    val strokeColor: Int,
    val textColor: Int,
    val strokeWidthDp: Int
)

private data class AirTonalButtonToneSpec(
    val fillTint: Float,
    val strokeTint: Float,
    val textColor: Int,
    val strokeWidthDp: Int
)

/** The main screen and standalone windows share the same button color rules. */
internal fun MainUiHost.airTonalButtonStyle(
    tone: AirTonalButtonTone,
    buttonStyle: ButtonStyle = buttonStyle()
): AirTonalButtonStyle = airLyricsPalette.airTonalButtonStyle(tone, buttonStyle, isDarkTheme(), appearance.frame.darkFraction)

internal fun Context.airTonalButtonStyle(tone: AirTonalButtonTone): AirTonalButtonStyle {
    val dark = ThemeSettingsStore.isDark(this)
    return AirLyricsTheme.palette(dark, ThemeSettingsStore.getAccent(this))
        .airTonalButtonStyle(tone, ThemeSettingsStore.getButtonStyle(this), dark)
}

internal fun AirLyricsPalette.airTonalButtonStyle(
    tone: AirTonalButtonTone,
    buttonStyle: ButtonStyle,
    isDark: Boolean,
    darkFraction: Float = if (isDark) 1f else 0f
): AirTonalButtonStyle = when (buttonStyle) {
    ButtonStyle.SOLID -> solidButtonStyle(tone)
    ButtonStyle.TRANSPARENT -> transparentButtonStyle(tone, darkFraction)
}

private fun AirLyricsPalette.solidButtonStyle(tone: AirTonalButtonTone): AirTonalButtonStyle = when (tone) {
    AirTonalButtonTone.PRIMARY -> AirTonalButtonStyle(
        accent, accent, onAccent, 0
    )
    AirTonalButtonTone.DEFAULT -> AirTonalButtonStyle(
        surfaceLight, stroke, text, AirUiTokens.Stroke.Hairline
    )
    AirTonalButtonTone.SELECTED -> AirTonalButtonStyle(
        accent, accentLight, onAccent, AirUiTokens.Stroke.Hairline
    )
    AirTonalButtonTone.SOURCE_SELECTED -> AirTonalButtonStyle(
        accent, accentLight, onAccent, AirUiTokens.Stroke.Selected
    )
    AirTonalButtonTone.SUCCESS -> AirTonalButtonStyle(
        accentSoft, accentSoft, onAccent, 0
    )
    AirTonalButtonTone.DANGER -> AirTonalButtonStyle(
        surfaceLight, danger, danger, AirUiTokens.Stroke.Hairline
    )
}

private fun AirLyricsPalette.transparentButtonStyle(tone: AirTonalButtonTone, darkFraction: Float): AirTonalButtonStyle {
    fun shade(light: Float, dark: Float) = light + (dark - light) * darkFraction
    val spec = when (tone) {
        AirTonalButtonTone.PRIMARY, AirTonalButtonTone.DEFAULT -> AirTonalButtonToneSpec(
            shade(AirUiTokens.Button.DefaultFillLight, AirUiTokens.Button.DefaultFillDark),
            shade(AirUiTokens.Button.DefaultStrokeLight, AirUiTokens.Button.DefaultStrokeDark),
            textStrong,
            AirUiTokens.Stroke.Hairline
        )
        AirTonalButtonTone.SELECTED, AirTonalButtonTone.SOURCE_SELECTED, AirTonalButtonTone.SUCCESS -> AirTonalButtonToneSpec(
            shade(AirUiTokens.Button.SelectedFillLight, AirUiTokens.Button.SelectedFillDark),
            shade(AirUiTokens.Button.SelectedStrokeLight, AirUiTokens.Button.SelectedStrokeDark),
            textStrong,
            AirUiTokens.Stroke.Selected
        )
        AirTonalButtonTone.DANGER -> AirTonalButtonToneSpec(
            shade(AirUiTokens.Button.DangerFillLight, AirUiTokens.Button.DangerFillDark),
            AirUiTokens.Button.DangerStroke,
            danger,
            AirUiTokens.Stroke.Hairline
        )
    }
    val tintColor = when (tone) {
        AirTonalButtonTone.DANGER -> danger
        AirTonalButtonTone.SUCCESS -> accentSoft
        else -> accent
    }
    return AirTonalButtonStyle(
        fillColor = ColorUtils.blendARGB(surfaceLight, tintColor, spec.fillTint),
        strokeColor = ColorUtils.blendARGB(stroke, tintColor, spec.strokeTint),
        textColor = spec.textColor,
        strokeWidthDp = spec.strokeWidthDp
    )
}

internal fun MainUiHost.styleAirTonalButton(
    view: View,
    tone: AirTonalButtonTone = AirTonalButtonTone.DEFAULT,
    radiusDp: Int = AirUiTokens.Radius.Md,
    buttonStyle: ButtonStyle? = null,
    selectionOutline: Boolean = false
) {
    bindAppearance(view) {
        applyAirTonalButtonStyle(it, tone, radiusDp, buttonStyle ?: buttonStyle(), selectionOutline)
    }
}

/** Color-only update; keeps the existing shape, measurement, listeners and focus. */
internal fun MainUiHost.applyAirTonalButtonStyle(
    view: View,
    tone: AirTonalButtonTone = AirTonalButtonTone.DEFAULT,
    radiusDp: Int = AirUiTokens.Radius.Md,
    buttonStyle: ButtonStyle = buttonStyle(),
    selectionOutline: Boolean = false
) {
    val style = airTonalButtonStyle(tone, buttonStyle)
    val shape = (view.background as? GradientDrawable) ?: GradientDrawable().also { view.background = it }
    shape.cornerRadius = dp(radiusDp).toFloat()
    shape.setColor(style.fillColor)
    shape.setStroke(
        dp(if (selectionOutline) AirUiTokens.Stroke.Selected else style.strokeWidthDp),
        if (selectionOutline) {
            if (buttonStyle == ButtonStyle.SOLID) colorOnAccent else colorTextStrong
        } else style.strokeColor
    )
    (view as? TextView)?.setTextColor(style.textColor)
}
