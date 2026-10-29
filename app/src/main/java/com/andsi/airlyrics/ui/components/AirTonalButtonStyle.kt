package com.andsi.airlyrics.ui.components

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.core.model.ButtonStyle
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentLight
import com.andsi.airlyrics.ui.theme.colorDanger
import com.andsi.airlyrics.ui.theme.colorOnAccent
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorText
import com.andsi.airlyrics.ui.theme.colorTextStrong

internal enum class AirTonalButtonTone {
    PRIMARY,
    DEFAULT,
    SELECTED,
    SOURCE_SELECTED,
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

/** One shared resolver for the current tonal look and the pre-polish solid look. */
internal fun MainUiHost.airTonalButtonStyle(
    tone: AirTonalButtonTone,
    buttonStyle: ButtonStyle = buttonStyle()
): AirTonalButtonStyle = when (buttonStyle) {
    ButtonStyle.SOLID -> solidButtonStyle(tone)
    ButtonStyle.TRANSPARENT -> transparentButtonStyle(tone)
}

private fun MainUiHost.solidButtonStyle(tone: AirTonalButtonTone): AirTonalButtonStyle = when (tone) {
    AirTonalButtonTone.PRIMARY -> AirTonalButtonStyle(
        colorAccent, colorAccent, colorOnAccent, 0
    )
    AirTonalButtonTone.DEFAULT -> AirTonalButtonStyle(
        colorSurfaceLight, colorStroke, colorText, AirUiTokens.Stroke.Hairline
    )
    AirTonalButtonTone.SELECTED -> AirTonalButtonStyle(
        colorAccent, colorAccentLight, colorOnAccent, AirUiTokens.Stroke.Hairline
    )
    AirTonalButtonTone.SOURCE_SELECTED -> AirTonalButtonStyle(
        colorAccent, colorAccentLight, colorOnAccent, AirUiTokens.Stroke.Selected
    )
    AirTonalButtonTone.DANGER -> AirTonalButtonStyle(
        colorSurfaceLight, colorDanger, colorDanger, AirUiTokens.Stroke.Hairline
    )
}

private fun MainUiHost.transparentButtonStyle(tone: AirTonalButtonTone): AirTonalButtonStyle {
    val dark = isDarkTheme()
    val spec = when (tone) {
        AirTonalButtonTone.PRIMARY, AirTonalButtonTone.DEFAULT -> AirTonalButtonToneSpec(
            if (dark) AirUiTokens.Button.DefaultFillDark else AirUiTokens.Button.DefaultFillLight,
            if (dark) AirUiTokens.Button.DefaultStrokeDark else AirUiTokens.Button.DefaultStrokeLight,
            colorTextStrong,
            AirUiTokens.Stroke.Hairline
        )
        AirTonalButtonTone.SELECTED, AirTonalButtonTone.SOURCE_SELECTED -> AirTonalButtonToneSpec(
            if (dark) AirUiTokens.Button.SelectedFillDark else AirUiTokens.Button.SelectedFillLight,
            if (dark) AirUiTokens.Button.SelectedStrokeDark else AirUiTokens.Button.SelectedStrokeLight,
            colorTextStrong,
            AirUiTokens.Stroke.Selected
        )
        AirTonalButtonTone.DANGER -> AirTonalButtonToneSpec(
            if (dark) AirUiTokens.Button.DangerFillDark else AirUiTokens.Button.DangerFillLight,
            AirUiTokens.Button.DangerStroke,
            colorDanger,
            AirUiTokens.Stroke.Hairline
        )
    }
    val tintColor = if (tone == AirTonalButtonTone.DANGER) colorDanger else colorAccent
    val baseColor = colorSurfaceLight
    return AirTonalButtonStyle(
        fillColor = ColorUtils.blendARGB(baseColor, tintColor, spec.fillTint),
        strokeColor = ColorUtils.blendARGB(colorStroke, tintColor, spec.strokeTint),
        textColor = spec.textColor,
        strokeWidthDp = spec.strokeWidthDp
    )
}

internal fun MainUiHost.styleAirTonalButton(
    view: View,
    tone: AirTonalButtonTone = AirTonalButtonTone.DEFAULT,
    radiusDp: Int = AirUiTokens.Radius.Md,
    buttonStyle: ButtonStyle = buttonStyle(),
    selectionOutline: Boolean = false
) {
    val style = airTonalButtonStyle(tone, buttonStyle)
    view.background = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(style.fillColor)
        if (selectionOutline) {
            setStroke(
                dp(AirUiTokens.Stroke.Selected),
                if (buttonStyle == ButtonStyle.SOLID) colorOnAccent else colorTextStrong
            )
        } else if (style.strokeWidthDp > 0) {
            setStroke(dp(style.strokeWidthDp), style.strokeColor)
        }
    }
    (view as? TextView)?.setTextColor(style.textColor)
}
