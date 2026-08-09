package com.andsi.airlyrics.ui.components

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.MainUiHost

/** Shared rendering helpers for the app's local 24dp Material Symbols. */
internal fun MainUiHost.airIconView(
    @DrawableRes iconRes: Int,
    @ColorInt tint: Int,
    contentDescription: CharSequence? = null
): ImageView {
    return ImageView(this).apply {
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(tint)
        scaleType = ImageView.ScaleType.CENTER
        this.contentDescription = contentDescription
        importantForAccessibility = if (contentDescription == null) {
            ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
        } else {
            ImageView.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
    }
}

internal fun TextView.setAirStartIcon(
    host: MainUiHost,
    @DrawableRes iconRes: Int?,
    @ColorInt tint: Int,
    paddingDp: Int = AirUiTokens.Space.Lg
) {
    val icon = iconRes?.let { host.airIconDrawable(it, tint) }
    setCompoundDrawablesRelative(icon, null, null, null)
    compoundDrawablePadding = if (icon == null) 0 else host.dp(paddingDp)
}

internal fun TextView.setAirTopIcon(
    host: MainUiHost,
    @DrawableRes iconRes: Int?,
    @ColorInt tint: Int,
    paddingDp: Int = AirUiTokens.Space.Sm
) {
    val icon = iconRes?.let { host.airIconDrawable(it, tint) }
    setCompoundDrawablesRelative(null, icon, null, null)
    compoundDrawablePadding = if (icon == null) 0 else host.dp(paddingDp)
}

private fun MainUiHost.airIconDrawable(
    @DrawableRes iconRes: Int,
    @ColorInt tint: Int
): Drawable {
    val drawable = requireNotNull(AppCompatResources.getDrawable(this, iconRes)).mutate()
    DrawableCompat.setTint(drawable, tint)
    val size = dp(AirUiTokens.Layout.IconSize)
    drawable.setBounds(0, 0, size, size)
    return drawable
}
