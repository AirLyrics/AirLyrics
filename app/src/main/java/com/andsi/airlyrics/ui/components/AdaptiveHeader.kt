package com.andsi.airlyrics.ui.components

import android.view.View
import android.view.ViewGroup
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.MainUiHost

/** Text actions reflow below the title; the close/icon action stays at the top end. */
internal fun MainUiHost.adaptiveHeader(title: View, actions: View? = null, endIcon: View? = null): View =
    AdaptiveLabelValueLayout(
        context = this,
        labelView = title,
        valueView = actions ?: View(this).apply { layoutParams = ViewGroup.LayoutParams(0, 0) },
        trailingView = endIcon,
        horizontalGapPx = if (actions == null) 0 else dp(AirUiTokens.Space.Xl),
        verticalGapPx = if (actions == null) 0 else dp(AirUiTokens.Space.Sm),
        trailingGapPx = dp(AirUiTokens.Space.Xl),
        trailingAtTop = true
    )
