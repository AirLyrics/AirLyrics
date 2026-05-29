package com.andsi.airlyrics.ui.pages.settings

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.app.*
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.theme.*

internal fun createThemeSettingsPage(activity: MainActivity): View  = with(activity) createThemeSettingsPage@ {
    val container = pageContainer(activity)
    container.addView(settingsBackHeader("主题外观"))

    container.addView(
        card(activity) {
            addView(bigText(activity, "显示模式"))
            addView(settingRow(activity, "当前模式", if (isDarkTheme()) "暗黑模式" else "白天模式"))
            addView(actionButton(activity, if (isDarkTheme()) "切换到白天模式" else "切换到暗黑模式") {
                uiActions.toggleThemeMode()
            })
        }
    )

    container.addView(
        card(activity) {
            addView(bigText(activity, "配色预览"))
            addView(themePalettePreview(activity))
        }
    )

    return scroll(activity, container)
}

private fun themePalettePreview(activity: MainActivity): View  = with(activity) themePalettePreview@ {
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        val colors = listOf(colorBackground, colorSurface, colorCard, colorAccent, colorAccentPink, colorAccentMint, colorTextStrong)
        colors.forEach { color ->
            addView(TextView(activity).apply {
                text = ""
                layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                }
                background = GradientDrawable().apply {
                    cornerRadius = dp(13).toFloat()
                    setColor(color)
                    setStroke(dp(1), colorStroke)
                }
            })
        }
    }
}

