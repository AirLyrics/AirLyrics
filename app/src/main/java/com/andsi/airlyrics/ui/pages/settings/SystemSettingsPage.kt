package com.andsi.airlyrics.ui.pages.settings

import com.andsi.airlyrics.R

import android.graphics.Typeface
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorOnAccent
import com.andsi.airlyrics.ui.theme.colorIconOnAccent
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextStrong
import com.andsi.airlyrics.ui.theme.applyAirThemeTint
import android.graphics.drawable.GradientDrawable
import com.andsi.airlyrics.design.tokens.AirUiTokens

internal fun createSystemSettingsPage(activity: MainUiHost): View  = with(activity) createSystemSettingsPage@ {
    val container = pageContainer(activity)
    container.addView(settingsBackHeader(getString(R.string.ui_system)))

    container.addView(languageChoiceCard(activity))
    container.addView(toasterMuteCard(activity))

    container.addView(
        card(activity) {
            addView(bigText(activity, getString(R.string.ui_permissions)))
            addView(settingRow(activity, getString(R.string.ui_overlay), if (Settings.canDrawOverlays(activity)) getString(R.string.ui_on) else getString(R.string.ui_off)))
            addView(settingRow(activity, getString(R.string.ui_notify), if (hasNotificationPermission()) getString(R.string.ui_on) else getString(R.string.ui_off)))
            addView(settingRow(activity, getString(R.string.ui_notif_access), if (hasNotificationListenerAccess()) getString(R.string.ui_on) else getString(R.string.ui_off)))
        }
    )

    container.addView(
        card(activity) {
            addView(bigText(activity, getString(R.string.ui_shortcuts)))
            addView(horizontalButtons(activity,
                getString(R.string.ui_overlay) to { uiActions.requestOverlayPermission() },
                getString(R.string.ui_notify) to { uiActions.requestNotificationPermission() }
            ))
            addView(actionButton(activity, getString(R.string.ui_open_access_settings)) {
                uiActions.openNotificationListenerSettings()
            })
        }
    )

    return scroll(activity, container)
}

private fun toasterMuteCard(activity: MainUiHost): View = with(activity) toasterMuteCard@ {
    val toasterSwitch = SwitchCompat(activity).apply {
        isChecked = isToasterMuted()
        applyAirThemeTint(activity)
        contentDescription = getString(R.string.ui_toaster_mute)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    return card(activity) {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        enableSoftPressFeedback(AirUiTokens.Motion.DefaultPressScale)

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Lg), 0)
            }
            addView(bigText(activity, getString(R.string.ui_toaster_mute)))
        })

        addView(toasterSwitch)

        toasterSwitch.setOnCheckedChangeListener { _, checked ->
            setToasterMuted(checked)
        }
        setOnClickListener {
            toasterSwitch.isChecked = !toasterSwitch.isChecked
        }
    }
}

private fun languageChoiceCard(activity: MainUiHost): View = with(activity) languageChoiceCard@ {
    return card(activity) {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(AirUiTokens.Space.CardH), dp(AirUiTokens.Space.CardH), dp(AirUiTokens.Space.CardH), dp(AirUiTokens.Space.CardH))
        isClickable = true
        isFocusable = true
        enableSoftPressFeedback(AirUiTokens.Motion.DefaultPressScale)
        setOnClickListener { showLanguageDialog(activity) }

        addView(TextView(activity).apply {
            text = getString(R.string.ui_language)
            textSize = AirUiTokens.TextSize.PageTitle - 4f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        addView(TextView(activity).apply {
            text = languageSettingsState().displayName
            textSize = AirUiTokens.TextSize.Button
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAccent)
        })

        addView(airIconView(R.drawable.ic_air_chevron_right, colorAccent).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(AirUiTokens.Layout.IconSize),
                dp(AirUiTokens.Layout.IconSize)
            ).apply {
                setMargins(dp(AirUiTokens.Space.Lg), 0, 0, 0)
            }
        })
    }
}

private fun showLanguageDialog(activity: MainUiHost) = with(activity) showLanguageDialog@ {
    val languageState = languageSettingsState()
    lateinit var dialog: android.app.Dialog
    dialog = showAirDialog(
        title = getString(R.string.ui_language),
        message = null,
        positiveText = null,
        body = {
            val selectMode: (String) -> Unit = { mode ->
                dialog.dismiss()
                if (mode != languageState.currentMode) {
                    setLanguageMode(mode)
                    activity.refreshAfterLanguageChanged()
                }
            }
            languageState.options.forEach { option ->
                addLanguageOption(activity, option.title, option.mode, languageState.currentMode, selectMode)
            }
        }
    )
}

private fun LinearLayout.addLanguageOption(
    activity: MainUiHost,
    title: String,
    mode: String,
    currentMode: String,
    onSelect: (String) -> Unit
) {
    val selected = mode == currentMode
    addView(TextView(activity).apply {
        text = title
        textSize = AirUiTokens.TextSize.Button
        typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setTextColor(if (selected) activity.colorOnAccent else activity.colorTextStrong)
        setAirStartIcon(
            host = activity,
            iconRes = R.drawable.ic_air_check.takeIf { selected },
            tint = if (selected) activity.colorIconOnAccent else activity.colorTextStrong
        )
        gravity = Gravity.CENTER_VERTICAL
        setPadding(activity.dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Lg), activity.dp(AirUiTokens.Space.ControlV), activity.dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Lg), activity.dp(AirUiTokens.Space.ControlV))
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, activity.dp(AirUiTokens.Space.Xxl), 0, 0)
        layoutParams = params
        background = GradientDrawable().apply {
            cornerRadius = activity.dp(AirUiTokens.Radius.Md).toFloat()
            setColor(if (selected) activity.colorAccent else activity.colorSurfaceLight)
            setStroke(activity.dp(AirUiTokens.Stroke.Hairline), if (selected) activity.colorAccent else activity.colorStroke)
        }
        enableSoftPressFeedback(AirUiTokens.Motion.DefaultPressScale)
        setOnClickListener {
            onSelect(mode)
        }
    })
}
