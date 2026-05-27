package com.andsi.airlyrics.ui.pages.settings

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.MainActivity
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.theme.*

internal fun MainActivity.createThemeSettingsPage(): View {
    val container = pageContainer()
    container.addView(settingsBackHeader("主题外观", "集中管理白天 / 暗黑模式和主界面的视觉调色盘。"))

    container.addView(
        card {
            addView(bigText("显示模式"))
            addView(settingRow("当前模式", if (isDarkTheme()) "暗黑模式" else "白天模式"))
            addView(actionButton(if (isDarkTheme()) "切换到白天模式" else "切换到暗黑模式") {
                toggleThemeMode()
            })
            addView(smallHint("右上角的太阳 / 月亮按钮使用同一份主题设置。"))
        }
    )

    container.addView(
        card {
            addView(bigText("当前调色板"))
            addView(themePalettePreview())
            addView(settingRow("强调色", colorToHex(colorAccent)))
            addView(settingRow("卡片色", colorToHex(colorCard)))
            addView(settingRow("背景色", colorToHex(colorBackground)))
            addView(smallHint("颜色定义集中在 ui/theme/AirLyricsTheme.kt，设置读写集中在 core/settings/ThemeSettingsStore.kt。"))
        }
    )

    return scroll(container)
}

private fun MainActivity.themePalettePreview(): View {
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        val colors = listOf(colorBackground, colorSurface, colorCard, colorAccent, colorAccentPink, colorAccentMint, colorTextStrong)
        colors.forEach { color ->
            addView(TextView(this@themePalettePreview).apply {
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

private fun colorToHex(color: Int): String {
    return "#%06X".format(0xFFFFFF and color)
}
