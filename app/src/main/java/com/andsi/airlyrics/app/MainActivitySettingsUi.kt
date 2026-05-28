package com.andsi.airlyrics.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.ui.components.bigText
import com.andsi.airlyrics.ui.components.card
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.normalText
import com.andsi.airlyrics.ui.components.smallHint
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong

internal fun MainActivity.settingsHomeHeader(): View {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(14))

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = "设置"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextStrong)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(activity.themeToggleButton())
        })

        addView(TextView(activity).apply {
            text = "把选项收进轻飘飘的小抽屉里，需要时再打开。"
            textSize = 14f
            setTextColor(colorTextMuted)
            setPadding(0, dp(4), 0, 0)
        })
    }
}

internal fun MainActivity.settingsBackHeader(title: String, subtitle: String): View {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(14))
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = "‹ 设置"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorAccent)
                setPadding(0, 0, 0, dp(10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                enableSoftPressFeedback(0.94f)
                setOnClickListener {
                    uiActions.backToSettingsHome()
                }
            })
        })
        addView(TextView(activity).apply {
            text = title
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
        })
        addView(TextView(activity).apply {
            text = subtitle
            textSize = 14f
            setTextColor(colorTextMuted)
            setPadding(0, dp(4), 0, 0)
        })
    }
}

internal fun MainActivity.themeToggleButton(): TextView {
    val activity = this
    return TextView(this).apply {
        text = if (isDarkTheme()) "☀" else "☾"
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(colorAccent)
        contentDescription = if (isDarkTheme()) "切换到白天模式" else "切换到暗黑模式"
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            setMargins(dp(10), 0, 0, 0)
        }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorSurfaceLight)
            setStroke(dp(1), colorStroke)
        }
        elevation = dp(2).toFloat()
        enableSoftPressFeedback(0.9f)
        setOnClickListener {
            uiActions.toggleThemeMode()
        }
    }
}

internal fun MainActivity.settingsCategoryCard(
    title: String,
    subtitle: String,
    status: String,
    accent: Int,
    onClick: () -> Unit
): View {
    val activity = this
    return card(this) {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        enableSoftPressFeedback(0.985f)
        setOnClickListener { onClick() }

        addView(TextView(activity).apply {
            text = "✦"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                setMargins(0, 0, dp(14), 0)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
            }
        })

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            addView(bigText(activity, title))
            addView(normalText(activity, subtitle))
            addView(smallHint(activity, status))
        })

        addView(TextView(activity).apply {
            text = "›"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAccent)
        })
    }
}

internal fun MainActivity.localLyricsRow(item: LyricsStorage.LocalLyricsItem): View {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, dp(10), 0, 0)
        layoutParams = params
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(colorSurfaceLight)
            setStroke(dp(1), colorStroke)
        }
        addView(TextView(activity).apply {
            text = item.displayTitle
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
        })
        addView(TextView(activity).apply {
            text = "${item.displaySubtitle} · ${LyricsStorage.formatLocalLyricsItem(item)}"
            textSize = 12f
            setTextColor(colorTextMuted)
            setPadding(0, dp(4), 0, 0)
        })
    }
}

internal fun MainActivity.changelogItem(title: String, body: String): View {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(10), 0, dp(2))
        addView(TextView(activity).apply {
            text = title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
        })
        addView(TextView(activity).apply {
            text = body
            textSize = 13f
            setTextColor(colorTextMuted)
            setPadding(0, dp(3), 0, 0)
        })
    }
}

internal fun MainActivity.permissionSummary(): String {
    val opened = listOf(
        Settings.canDrawOverlays(this),
        hasNotificationPermission(),
        hasNotificationListenerAccess()
    ).count { it }
    return "已开启 $opened / 3 项基础权限"
}

internal fun MainActivity.getAppVersionName(): String {
    return try {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        packageInfo.versionName ?: "1.0"
    } catch (e: Exception) {
        "1.0"
    }
}

internal fun MainActivity.openUrl(url: String) {
    AppNavigator.openUrl(this, url)
}

