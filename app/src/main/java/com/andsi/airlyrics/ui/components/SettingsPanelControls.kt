package com.andsi.airlyrics.ui.components

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatSeekBar
import androidx.appcompat.widget.SwitchCompat
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong

/** The same value layout and drag target are used for style and RGB sliders. */
@SuppressLint("ViewConstructor")
internal class SettingsSliderRow(
    host: MainUiHost,
    title: String,
    initialValue: String,
    compact: Boolean = false
) : LinearLayout(host) {
    val valueView = TextView(host).apply {
        text = initialValue
        textSize = AirUiTokens.TextSize.Button
        typeface = Typeface.DEFAULT_BOLD
        fontFeatureSettings = "tnum"
        setTextColor(host.colorTextStrong)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun updateValue(value: String) {
        valueView.text = value
        androidx.core.view.ViewCompat.setStateDescription(seekBar, value)
    }

    val seekBar = object : AppCompatSeekBar(host) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    parent?.requestDisallowInterceptTouchEvent(false)
            }
            return super.dispatchTouchEvent(event)
        }
    }.apply {
        contentDescription = title
        minimumHeight = host.dp(AirUiTokens.Layout.IconTouchSize)
        thumbTintList = ColorStateList.valueOf(host.colorAccent)
        progressTintList = ColorStateList.valueOf(host.colorAccent)
        splitTrack = false
        androidx.core.view.ViewCompat.setStateDescription(this, initialValue)
    }

    init {
        orientation = VERTICAL
        setPadding(0, host.dp(AirUiTokens.Space.Xl), 0, host.dp(AirUiTokens.Space.Sm))
        val label = TextView(host).apply {
            text = title
            textSize = AirUiTokens.TextSize.Button
            setTextColor(host.colorTextStrong)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        if (compact) {
            valueView.minWidth = kotlin.math.ceil(valueView.paint.measureText(
                if (initialValue.endsWith("%")) "200%" else "255"
            ).toDouble()).toInt()
            addView(AdaptiveSliderLayout(host, label, seekBar, valueView),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        } else {
            addView(AdaptiveLabelValueLayout(
                host, label, valueView,
                horizontalGapPx = host.dp(AirUiTokens.SettingsPanel.ControlGap),
                verticalGapPx = host.dp(AirUiTokens.Space.Sm),
                trailingGapPx = 0
            ), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(seekBar, LayoutParams(LayoutParams.MATCH_PARENT, host.dp(AirUiTokens.Layout.IconTouchSize)))
        }
    }
}

internal fun panelSectionLabel(host: MainUiHost, text: String): TextView = TextView(host).apply {
    this.text = text
    textSize = AirUiTokens.TextSize.BodySmall
    typeface = Typeface.DEFAULT_BOLD
    setTextColor(host.colorTextMuted)
    setPadding(0, host.dp(AirUiTokens.SettingsPanel.SectionGap), 0, host.dp(AirUiTokens.SettingsPanel.ControlGap))
    androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
}

internal fun panelSwitch(
    host: MainUiHost,
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
): SwitchCompat = settingSwitch(host, text, checked, onCheckedChange).apply {
    minHeight = host.dp(AirUiTokens.SettingsPanel.SwitchMinHeight)
    switchPadding = host.dp(AirUiTokens.Space.ButtonH)
    setPadding(0, host.dp(AirUiTokens.SettingsPanel.ControlGap), 0, host.dp(AirUiTokens.SettingsPanel.ControlGap))
}
