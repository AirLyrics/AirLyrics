package com.andsi.airlyrics.ui.components

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.core.model.ButtonStyle
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.AirLyricsPalette
import com.andsi.airlyrics.ui.theme.AirLyricsTheme
import com.andsi.airlyrics.ui.theme.appearancePreferences
import com.andsi.airlyrics.ui.theme.colorOnAccent
import com.andsi.airlyrics.ui.theme.colorTextStrong
import com.andsi.airlyrics.ui.theme.bindAppearance
import kotlin.math.roundToInt

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
) {
    fun interpolate(target: AirTonalButtonStyle, amount: Float): AirTonalButtonStyle {
        if (amount <= 0f) return this
        if (amount >= 1f) return target
        return AirTonalButtonStyle(
            ColorUtils.blendARGB(fillColor, target.fillColor, amount),
            ColorUtils.blendARGB(strokeColor, target.strokeColor, amount),
            ColorUtils.blendARGB(textColor, target.textColor, amount),
            (strokeWidthDp + (target.strokeWidthDp - strokeWidthDp) * amount).roundToInt(),
        )
    }
}

private data class AirTonalButtonToneSpec(
    val fillTint: Float,
    val strokeTint: Float,
    val textColor: Int,
    val strokeWidthDp: Int
)

internal fun MainUiHost.airTonalButtonStyle(
    tone: AirTonalButtonTone,
    buttonStyle: ButtonStyle? = null,
): AirTonalButtonStyle {
    val frame = appearance.frame
    fun style(mode: ButtonStyle) = frame.palette.airTonalButtonStyle(tone, mode, isDarkTheme(), frame.darkFraction)
    if (buttonStyle != null) return style(buttonStyle)
    val solid = frame.solidButtonFraction
    return when (solid) {
        0f -> style(ButtonStyle.TRANSPARENT)
        1f -> style(ButtonStyle.SOLID)
        else -> style(ButtonStyle.TRANSPARENT).interpolate(style(ButtonStyle.SOLID), solid)
    }
}

internal fun Context.airTonalButtonStyle(tone: AirTonalButtonTone): AirTonalButtonStyle {
    val (dark, accent, buttonStyle) = appearancePreferences()
    return AirLyricsTheme.palette(dark, accent).airTonalButtonStyle(tone, buttonStyle, dark)
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
) {
    bindAppearance(view) {
        applyAirTonalButtonStyle(it, tone, radiusDp, buttonStyle)
    }
}

internal fun MainUiHost.applyAirTonalButtonStyle(
    view: View,
    tone: AirTonalButtonTone = AirTonalButtonTone.DEFAULT,
    radiusDp: Int = AirUiTokens.Radius.Md,
    buttonStyle: ButtonStyle? = null,
    selectionOutlineAmount: Float = 0f,
) {
    val style = airTonalButtonStyle(tone, buttonStyle)
    val visual = if (selectionOutlineAmount > 0f) {
        style.interpolate(
            style.copy(
                strokeColor = if (buttonStyle == ButtonStyle.SOLID) colorOnAccent else colorTextStrong,
                strokeWidthDp = AirUiTokens.Stroke.Selected,
            ),
            selectionOutlineAmount,
        )
    } else style
    applyAirTonalButtonStyle(view, visual, radiusDp)
}

internal fun MainUiHost.applyAirTonalButtonStyle(
    view: View,
    style: AirTonalButtonStyle,
    radiusDp: Int = AirUiTokens.Radius.Md,
) {
    val shape = (view.background as? GradientDrawable) ?: GradientDrawable().also { view.background = it }
    shape.cornerRadius = dp(radiusDp).toFloat()
    shape.setColor(style.fillColor)
    shape.setStroke(dp(style.strokeWidthDp), style.strokeColor)
    (view as? TextView)?.setTextColor(style.textColor)
}
