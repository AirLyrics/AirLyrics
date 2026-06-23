package com.andsi.airlyrics.ui.pages.settings

import com.andsi.airlyrics.R

import android.graphics.Typeface
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.settings.store.LanguageSettingsStore
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextStrong
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.andsi.airlyrics.ui.tokens.AirUiTokens

internal fun createSystemSettingsPage(activity: MainUiHost): View  = with(activity) createSystemSettingsPage@ {
    val container = pageContainer(activity)
    container.addView(settingsBackHeader(getString(R.string.ui_system)))

    container.addView(languageChoiceCard(activity))

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
            text = LanguageSettingsStore.currentDisplayName(activity)
            textSize = AirUiTokens.TextSize.Button
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAccent)
        })

        addView(TextView(activity).apply {
            text = "  ›"
            textSize = AirUiTokens.TextSize.PageTitle
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAccent)
        })
    }
}

private fun showLanguageDialog(activity: MainUiHost) = with(activity) showLanguageDialog@ {
    val currentMode = LanguageSettingsStore.getMode(activity)
    lateinit var dialog: android.app.Dialog
    dialog = showAirDialog(
        title = getString(R.string.ui_language),
        message = null,
        positiveText = null,
        body = {
            val selectMode: (String) -> Unit = { mode ->
                LanguageSettingsStore.setMode(activity, mode)
                dialog.dismiss()
                activity.refreshAfterLanguageChanged()
            }
            addLanguageOption(activity, getString(R.string.ui_follow_system), getString(R.string.ui_follow_system), LanguageSettingsStore.MODE_SYSTEM, currentMode, selectMode)
            addLanguageOption(activity, getString(R.string.ui_chinese_simplified), getString(R.string.ui_chinese_simplified), LanguageSettingsStore.MODE_ZH_CN, currentMode, selectMode)
            addLanguageOption(activity, getString(R.string.ui_english), getString(R.string.ui_english), LanguageSettingsStore.MODE_EN, currentMode, selectMode)
        }
    )
}

private fun LinearLayout.addLanguageOption(
    activity: MainUiHost,
    title: String,
    subtitle: String,
    mode: String,
    currentMode: String,
    onSelect: (String) -> Unit
) {
    val selected = mode == currentMode
    addView(TextView(activity).apply {
        text = if (selected) "✓ $title\n$subtitle" else "$title\n$subtitle"
        textSize = AirUiTokens.TextSize.Button
        typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setTextColor(if (selected) Color.WHITE else activity.colorTextStrong)
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
