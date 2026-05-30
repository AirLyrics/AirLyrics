package com.andsi.airlyrics.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.i18n.localizeText
import com.andsi.airlyrics.ui.components.bigText
import com.andsi.airlyrics.ui.components.card
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.normalText
import com.andsi.airlyrics.ui.components.smallHint
import com.andsi.airlyrics.ui.components.showAirDialog
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentMint
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
                text = localizeText("设置")
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextStrong)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(activity.themeToggleButton())
        })
    }
}

internal fun MainActivity.settingsBackHeader(title: String, subtitle: String = ""): View {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(14))
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = localizeText("‹ 设置")
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
            text = localizeText(title)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
        })
        if (subtitle.isNotBlank()) {
            addView(TextView(activity).apply {
                text = localizeText(subtitle)
                textSize = 14f
                setTextColor(colorTextMuted)
                setPadding(0, dp(4), 0, 0)
            })
        }
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
        contentDescription = if (isDarkTheme()) localizeText("切换到白天模式") else localizeText("切换到暗黑模式")
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
    iconRes: Int,
    onClick: () -> Unit
): View {
    val activity = this
    return card(this) {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        enableSoftPressFeedback(0.985f)
        setOnClickListener { onClick() }

        addView(FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                setMargins(0, 0, dp(14), 0)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
            }
            addView(ImageView(activity).apply {
                setImageResource(iconRes)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER
                layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
            })
        })

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
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
        enableSoftPressFeedback(0.98f)
        addView(TextView(activity).apply {
            text = item.displayTitle
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
        })
        addView(TextView(activity).apply {
            text = "${localizeText(item.displaySubtitle)} · ${localizeText(item.lyricsTypeText)}"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAccentMint)
            setPadding(0, dp(4), 0, 0)
        })
        addView(TextView(activity).apply {
            text = localizeText(LyricsStorage.formatLocalLyricsItem(item))
            textSize = 12f
            setTextColor(colorTextMuted)
            setPadding(0, dp(2), 0, 0)
        })
        setOnClickListener {
            val rawLyrics = LyricsStorage.readLocalLyricsItemText(activity, item)
            if (rawLyrics == null) {
                Toast.makeText(activity, localizeText("无法读取这份歌词"), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val editor = EditText(activity).apply {
                setText(rawLyrics)
                textSize = 13f
                minLines = 8
                maxLines = 18
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setHorizontallyScrolling(false)
                setSelection(0)
                setTextColor(colorTextStrong)
                setHintTextColor(colorTextMuted)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(colorSurfaceLight)
                    setStroke(dp(1), colorStroke)
                }
            }

            val canEdit = item.hasPlainLyrics && !item.name.endsWith(".karaoke.json", ignoreCase = true)
            activity.showAirDialog(
                title = item.displayTitle,
                message = if (canEdit) localizeText("可预览，也可以直接修改普通 LRC 内容。").toString() else localizeText("这份逐字歌词只能预览。").toString(),
                positiveText = if (canEdit) localizeText("保存修改").toString() else null,
                negativeText = localizeText("关闭").toString(),
                body = {
                    addView(editor)
                }
            ) {
                val saved = LyricsStorage.updateLocalLyricsItemText(activity, item, editor.text.toString())
                Toast.makeText(
                    activity,
                    if (saved) localizeText("歌词已保存") else localizeText("保存失败，请确认内容是 [00:12.34]歌词 格式"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

internal fun MainActivity.changelogItem(title: String, body: String): View {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(10), 0, dp(2))
        addView(TextView(activity).apply {
            text = localizeText(title)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
        })
        addView(TextView(activity).apply {
            text = localizeText(body)
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
    return localizeText("已开启 $opened / 3 项基础权限").toString()
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


internal fun MainActivity.refreshAfterLanguageChanged() {
    setContentView(createMainView())
    renderCurrentPage()
    reloadFloatingLyrics()
}
