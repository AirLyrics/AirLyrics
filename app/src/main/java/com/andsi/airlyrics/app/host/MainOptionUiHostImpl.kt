package com.andsi.airlyrics.app.host

import com.andsi.airlyrics.ui.components.AdaptiveGridLayout
import com.andsi.airlyrics.ui.components.AirTonalButtonTone
import com.andsi.airlyrics.ui.components.airTonalButtonStyle
import com.andsi.airlyrics.ui.components.styleAirTonalButton
import com.andsi.airlyrics.R
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.playTinyPulse
import com.andsi.airlyrics.ui.components.setAirStartIcon
import com.andsi.airlyrics.ui.model.KeyedOptionItem
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.model.OptionItem
import com.andsi.airlyrics.design.tokens.AirUiTokens

internal fun MainUiHost.optionGridImpl(items: List<OptionItem>): LinearLayout {
    return optionButtonGridImpl(items.map { item -> optionButton(item) })
}

internal fun MainUiHost.optionButtonGridImpl(buttons: List<TextView>): LinearLayout {
    return AdaptiveGridLayout(this, AirUiTokens.Layout.OptionColumns, dp(AirUiTokens.Space.Lg * 2), dp(AirUiTokens.Space.Xxl)).apply {
        setPadding(0, dp(AirUiTokens.Space.Xxl), 0, 0)
        buttons.forEach { addView(it) }
    }
}

internal fun MainUiHost.liveOptionGridImpl(items: List<KeyedOptionItem>): LinearLayout {
    val buttons = mutableListOf<Pair<KeyedOptionItem, TextView>>()
    items.forEach { item ->
        val button = optionButton(OptionItem(item.title, item.selected) {
            item.action()
            buttons.forEach { (option, view) ->
                applyOptionButtonState(view, option.title, option.key == item.key)
            }
        })
        buttons.add(item to button)
    }
    return optionButtonGridImpl(buttons.map { it.second })
}

internal fun MainUiHost.optionButtonImpl(item: OptionItem): TextView {
    return TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = AirUiTokens.TextSize.Body
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.ControlV), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.ControlV))
        applyOptionButtonState(this, item.title, item.selected)
        // Option chips are touch targets, not keyboard targets. Taking focus makes Android run a
        // focus search (and requestChildRectangleOnScreen) on every tap, which scrolls whichever
        // scroll container is behind the panel.
        isFocusable = false
        isFocusableInTouchMode = false
        enableSoftPressFeedback(AirUiTokens.Motion.OptionPressScale)
        setOnClickListener {
            item.action()
            playTinyPulse(this)
        }
    }
}

internal fun MainUiHost.applyOptionButtonStateImpl(button: TextView, title: String, selected: Boolean) {
    button.text = title
    val tone = if (selected) AirTonalButtonTone.SELECTED else AirTonalButtonTone.DEFAULT
    val style = airTonalButtonStyle(tone)
    button.setAirStartIcon(
        host = this,
        iconRes = R.drawable.ic_air_check.takeIf { selected },
        tint = style.textColor
    )
    styleAirTonalButton(button, tone)
}
