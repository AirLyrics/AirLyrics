package com.andsi.airlyrics.app

import android.app.Dialog
import android.widget.LinearLayout
import com.andsi.airlyrics.ui.components.showAirConfirmDialog
import com.andsi.airlyrics.ui.components.showAirDialog
import com.andsi.airlyrics.ui.components.showAirInfoDialog
import com.andsi.airlyrics.ui.theme.colorAccent as hostColorAccent
import com.andsi.airlyrics.ui.theme.colorAccentLight as hostColorAccentLight
import com.andsi.airlyrics.ui.theme.colorAccentMint as hostColorAccentMint
import com.andsi.airlyrics.ui.theme.colorAccentPink as hostColorAccentPink
import com.andsi.airlyrics.ui.theme.colorAccentSoft as hostColorAccentSoft
import com.andsi.airlyrics.ui.theme.colorBackground as hostColorBackground
import com.andsi.airlyrics.ui.theme.colorBubble as hostColorBubble
import com.andsi.airlyrics.ui.theme.colorCard as hostColorCard
import com.andsi.airlyrics.ui.theme.colorStroke as hostColorStroke
import com.andsi.airlyrics.ui.theme.colorSurface as hostColorSurface
import com.andsi.airlyrics.ui.theme.colorSurfaceLight as hostColorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorText as hostColorText
import com.andsi.airlyrics.ui.theme.colorTextMuted as hostColorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong as hostColorTextStrong

internal val MainActivity.colorBackground: Int
    get() = uiHost.hostColorBackground
internal val MainActivity.colorSurface: Int
    get() = uiHost.hostColorSurface
internal val MainActivity.colorSurfaceLight: Int
    get() = uiHost.hostColorSurfaceLight
internal val MainActivity.colorCard: Int
    get() = uiHost.hostColorCard
internal val MainActivity.colorBubble: Int
    get() = uiHost.hostColorBubble
internal val MainActivity.colorStroke: Int
    get() = uiHost.hostColorStroke
internal val MainActivity.colorAccent: Int
    get() = uiHost.hostColorAccent
internal val MainActivity.colorAccentLight: Int
    get() = uiHost.hostColorAccentLight
internal val MainActivity.colorAccentSoft: Int
    get() = uiHost.hostColorAccentSoft
internal val MainActivity.colorAccentPink: Int
    get() = uiHost.hostColorAccentPink
internal val MainActivity.colorAccentMint: Int
    get() = uiHost.hostColorAccentMint
internal val MainActivity.colorTextStrong: Int
    get() = uiHost.hostColorTextStrong
internal val MainActivity.colorText: Int
    get() = uiHost.hostColorText
internal val MainActivity.colorTextMuted: Int
    get() = uiHost.hostColorTextMuted

internal fun MainActivity.showAirInfoDialog(
    title: String,
    message: String,
    buttonText: String? = null
): Dialog = uiHost.showAirInfoDialog(title, message, buttonText)

internal fun MainActivity.showAirConfirmDialog(
    title: String,
    message: String,
    positiveText: String,
    negativeText: String? = null,
    onPositive: () -> Unit
): Dialog = uiHost.showAirConfirmDialog(title, message, positiveText, negativeText, onPositive)

internal fun MainActivity.showAirDialog(
    title: String,
    message: String? = null,
    positiveText: String? = "__airlyrics_default_positive__",
    negativeText: String? = null,
    body: (LinearLayout.() -> Unit)? = null,
    onPositive: () -> Unit = {}
): Dialog = uiHost.showAirDialog(title, message, positiveText, negativeText, body, onPositive)
