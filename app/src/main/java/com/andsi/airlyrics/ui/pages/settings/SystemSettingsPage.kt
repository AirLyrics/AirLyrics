package com.andsi.airlyrics.ui.pages.settings

import android.graphics.Typeface
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.app.*
import com.andsi.airlyrics.settings.store.LanguageSettingsStore
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorCard
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.andsi.airlyrics.i18n.tr

internal fun createSystemSettingsPage(activity: MainActivity): View  = with(activity) createSystemSettingsPage@ {
    val container = pageContainer(activity)
    container.addView(settingsBackHeader(tr("系统与权限", "System")))

    container.addView(languageChoiceCard(activity))

    container.addView(
        card(activity) {
            addView(bigText(activity, tr("权限状态", "Permissions")))
            addView(settingRow(activity, tr("悬浮窗权限", "Overlay"), if (Settings.canDrawOverlays(activity)) tr("已开启", "On") else tr("未开启", "Off")))
            addView(settingRow(activity, tr("通知权限", "Notify"), if (hasNotificationPermission()) tr("已开启", "On") else tr("未开启", "Off")))
            addView(settingRow(activity, tr("通知访问权限", "Notif. access"), if (hasNotificationListenerAccess()) tr("已开启", "On") else tr("未开启", "Off")))
        }
    )

    container.addView(
        card(activity) {
            addView(bigText(activity, tr("快捷入口", "Shortcuts")))
            addView(horizontalButtons(activity,
                tr("悬浮窗权限", "Overlay") to { uiActions.requestOverlayPermission() },
                tr("通知权限", "Notify") to { uiActions.requestNotificationPermission() }
            ))
            addView(actionButton(activity, tr("打开通知访问设置", "Open access settings")) {
                uiActions.openNotificationListenerSettings()
            })
        }
    )

    return scroll(activity, container)
}

private fun languageChoiceCard(activity: MainActivity): View = with(activity) languageChoiceCard@ {
    return card(activity) {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        isClickable = true
        isFocusable = true
        enableSoftPressFeedback(0.97f)
        setOnClickListener { showLanguageDialog(activity) }

        addView(TextView(activity).apply {
            text = "Language"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        addView(TextView(activity).apply {
            text = LanguageSettingsStore.currentDisplayName(activity)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAccent)
        })

        addView(TextView(activity).apply {
            text = "  ›"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAccent)
        })
    }
}

private fun showLanguageDialog(activity: MainActivity) = with(activity) showLanguageDialog@ {
    val currentMode = LanguageSettingsStore.getMode(activity)
    lateinit var dialog: android.app.Dialog
    dialog = showAirDialog(
        title = "Language",
        message = null,
        positiveText = null,
        body = {
            val selectMode: (String) -> Unit = { mode ->
                LanguageSettingsStore.setMode(activity, mode)
                dialog.dismiss()
                activity.refreshAfterLanguageChanged()
            }
            addLanguageOption(activity, tr("跟随系统", "Follow system"), "Follow system", LanguageSettingsStore.MODE_SYSTEM, currentMode, selectMode)
            addLanguageOption(activity, tr("简体中文", "Chinese (Simplified)"), "Chinese (Simplified)", LanguageSettingsStore.MODE_ZH_CN, currentMode, selectMode)
            addLanguageOption(activity, "English", "English", LanguageSettingsStore.MODE_EN, currentMode, selectMode)
        }
    )
}

private fun LinearLayout.addLanguageOption(
    activity: MainActivity,
    title: String,
    subtitle: String,
    mode: String,
    currentMode: String,
    onSelect: (String) -> Unit
) {
    val selected = mode == currentMode
    addView(TextView(activity).apply {
        text = if (selected) "✓ $title\n$subtitle" else "$title\n$subtitle"
        textSize = 15f
        typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setTextColor(if (selected) Color.WHITE else activity.colorTextStrong)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(activity.dp(14), activity.dp(11), activity.dp(14), activity.dp(11))
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, activity.dp(10), 0, 0)
        layoutParams = params
        background = GradientDrawable().apply {
            cornerRadius = activity.dp(18).toFloat()
            setColor(if (selected) activity.colorAccent else activity.colorSurfaceLight)
            setStroke(activity.dp(1), if (selected) activity.colorAccent else activity.colorStroke)
        }
        enableSoftPressFeedback(0.97f)
        setOnClickListener {
            onSelect(mode)
        }
    })
}
