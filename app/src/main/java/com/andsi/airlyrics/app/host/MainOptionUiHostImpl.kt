package com.andsi.airlyrics.app.host

import com.andsi.airlyrics.R
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.playTinyPulse
import com.andsi.airlyrics.ui.components.setAirStartIcon
import com.andsi.airlyrics.ui.model.KeyedOptionItem
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.model.OptionItem
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentLight
import com.andsi.airlyrics.ui.theme.colorOnAccent
import com.andsi.airlyrics.ui.theme.colorIconOnAccent
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorText
import com.andsi.airlyrics.design.tokens.AirUiTokens

internal fun MainUiHost.optionGridImpl(items: List<OptionItem>): LinearLayout {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        items.chunked(AirUiTokens.Layout.OptionColumns).forEach { rowItems ->
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                rowItems.forEachIndexed { index, item ->
                    addView(optionButton(item).apply {
                        val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        params.setMargins(
                            if (index == 0) 0 else dp(AirUiTokens.Space.Lg),
                            dp(AirUiTokens.Space.Xxl),
                            if (index == rowItems.lastIndex) 0 else dp(AirUiTokens.Space.Lg),
                            0
                        )
                        layoutParams = params
                    })
                }
                if (rowItems.size == 1) {
                    addView(View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                            setMargins(dp(AirUiTokens.Space.Lg), 0, 0, 0)
                        }
                    })
                }
            })
        }
    }
}

internal fun MainUiHost.liveOptionGridImpl(items: List<KeyedOptionItem>): LinearLayout {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val buttons = mutableListOf<Pair<KeyedOptionItem, TextView>>()

        fun refreshSelection(selectedKey: String) {
            buttons.forEach { (item, button) ->
                applyOptionButtonState(button, item.title, item.key == selectedKey)
            }
        }

        items.chunked(AirUiTokens.Layout.OptionColumns).forEach { rowItems ->
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                rowItems.forEachIndexed { index, item ->
                    val button = TextView(activity).apply {
                        gravity = Gravity.CENTER
                        textSize = AirUiTokens.TextSize.Body
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.ControlV), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.ControlV))
                        applyOptionButtonState(this, item.title, item.selected)
                        enableSoftPressFeedback(AirUiTokens.Motion.OptionPressScale)
                        setOnClickListener {
                            item.action()
                            refreshSelection(item.key)
                            playTinyPulse(this)
                        }
                    }
                    buttons.add(item to button)
                    addView(button.apply {
                        val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        params.setMargins(
                            if (index == 0) 0 else dp(AirUiTokens.Space.Lg),
                            dp(AirUiTokens.Space.Xxl),
                            if (index == rowItems.lastIndex) 0 else dp(AirUiTokens.Space.Lg),
                            0
                        )
                        layoutParams = params
                    })
                }
                if (rowItems.size == 1) {
                    addView(View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                            setMargins(dp(AirUiTokens.Space.Lg), 0, 0, 0)
                        }
                    })
                }
            })
        }
    }
}

internal fun MainUiHost.optionButtonImpl(item: OptionItem): TextView {
    return TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = AirUiTokens.TextSize.Body
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.ControlV), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.ControlV))
        applyOptionButtonState(this, item.title, item.selected)
        enableSoftPressFeedback(AirUiTokens.Motion.OptionPressScale)
        setOnClickListener {
            item.action()
            playTinyPulse(this)
        }
    }
}

internal fun MainUiHost.applyOptionButtonStateImpl(button: TextView, title: String, selected: Boolean) {
    button.text = title
    button.setTextColor(if (selected) colorOnAccent else colorText)
    button.setAirStartIcon(
        host = this,
        iconRes = R.drawable.ic_air_check.takeIf { selected },
        tint = if (selected) colorIconOnAccent else colorText
    )
    button.background = GradientDrawable().apply {
        cornerRadius = dp(AirUiTokens.Radius.Md).toFloat()
        setColor(if (selected) colorAccent else colorSurfaceLight)
        setStroke(dp(AirUiTokens.Stroke.Hairline), if (selected) colorAccentLight else colorStroke)
    }
}
