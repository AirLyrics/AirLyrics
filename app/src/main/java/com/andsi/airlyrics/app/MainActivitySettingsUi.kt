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
import com.andsi.airlyrics.lyrics.parser.LrcParser
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
import com.andsi.airlyrics.i18n.tr
import com.andsi.airlyrics.i18n.localizedLocalLyricsMeta
import com.andsi.airlyrics.i18n.localizedLocalLyricsType
import com.andsi.airlyrics.i18n.localizedLocalLyricsSubtitle
import com.andsi.airlyrics.ui.tokens.AirUiTokens

internal fun MainActivity.settingsHomeHeader(): View {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Lg))

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = tr("设置", "Settings")
                textSize = AirUiTokens.TextSize.PageTitle
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
        setPadding(0, 0, 0, dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Lg))
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = tr("‹ 设置", "‹ Settings")
                textSize = AirUiTokens.TextSize.Body
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorAccent)
                setPadding(0, 0, 0, dp(AirUiTokens.Space.Xxl))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
                setOnClickListener {
                    uiActions.backToSettingsHome()
                }
            })
        })
        addView(TextView(activity).apply {
            text = localizeText(title)
            textSize = AirUiTokens.TextSize.PageTitle
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
        })
        if (subtitle.isNotBlank()) {
            addView(TextView(activity).apply {
                text = localizeText(subtitle)
                textSize = AirUiTokens.TextSize.Body
                setTextColor(colorTextMuted)
                setPadding(0, dp(AirUiTokens.Space.Sm), 0, 0)
            })
        }
    }
}

internal fun MainActivity.themeToggleButton(): TextView {
    val activity = this
    return TextView(this).apply {
        text = if (isDarkTheme()) "☀" else "☾"
        textSize = AirUiTokens.TextSize.PageTitle - 4f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(colorAccent)
        contentDescription = if (isDarkTheme()) tr("切换到白天模式", "Switch to light mode") else tr("切换到暗黑模式", "Switch to dark mode")
        layoutParams = LinearLayout.LayoutParams(dp(AirUiTokens.Layout.ThemeToggleSize), dp(AirUiTokens.Layout.ThemeToggleSize)).apply {
            setMargins(dp(AirUiTokens.Space.Xxl), 0, 0, 0)
        }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorSurfaceLight)
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
        }
        elevation = dp(AirUiTokens.Space.Xxs).toFloat()
        enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
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
        enableSoftPressFeedback(AirUiTokens.Motion.FloatingCardPressScale)
        setOnClickListener { onClick() }

        addView(FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(AirUiTokens.Layout.SettingsIconBubbleSize), dp(AirUiTokens.Layout.SettingsIconBubbleSize)).apply {
                setMargins(0, 0, dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Lg), 0)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
            }
            addView(ImageView(activity).apply {
                setImageResource(iconRes)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER
                layoutParams = FrameLayout.LayoutParams(dp(AirUiTokens.Radius.Card), dp(AirUiTokens.Radius.Card), Gravity.CENTER)
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
            textSize = AirUiTokens.Layout.ChevronTextSp
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAccent)
        })
    }
}

internal fun MainActivity.localLyricsRow(
    item: LyricsStorage.LocalLyricsItem,
    onLyricsSaved: (() -> Unit)? = null,
    badgeText: CharSequence? = null
): View {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.Xxl), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.Xxl))
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, dp(AirUiTokens.Space.Xxl), 0, 0)
        layoutParams = params
        background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Md).toFloat()
            setColor(colorSurfaceLight)
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
        }
        enableSoftPressFeedback(AirUiTokens.Motion.DefaultPressScale + 0.01f)
        if (!badgeText.isNullOrBlank()) {
            addView(TextView(activity).apply {
                text = localizeText(badgeText)
                textSize = AirUiTokens.TextSize.Tiny
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorAccent)
                setPadding(0, 0, 0, dp(AirUiTokens.Space.Sm))
            })
        }
        addView(TextView(activity).apply {
            text = item.displayTitle
            textSize = AirUiTokens.TextSize.Body
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
        })
        addView(TextView(activity).apply {
            text = "${localizedLocalLyricsSubtitle(item)} · ${localizedLocalLyricsType(item)}"
            textSize = AirUiTokens.TextSize.Caption
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAccentMint)
            setPadding(0, dp(AirUiTokens.Space.Sm), 0, 0)
        })
        addView(TextView(activity).apply {
            text = localizedLocalLyricsMeta(item)
            textSize = AirUiTokens.TextSize.Caption
            setTextColor(colorTextMuted)
            setPadding(0, dp(AirUiTokens.Space.Xxs), 0, 0)
        })
        setOnClickListener {
            activity.openLocalLyricsTargetPicker(item, onLyricsSaved)
        }
    }
}


private fun MainActivity.openLocalLyricsTargetPicker(
    item: LyricsStorage.LocalLyricsItem,
    onLyricsSaved: (() -> Unit)?
) {
    when {
        item.hasPlainLyrics && item.hasKaraokeLyrics -> {
            var pickerDialog: android.app.Dialog? = null
            pickerDialog = showAirDialog(
                title = item.displayTitle,
                message = tr(
                    "这首歌同时有普通歌词和逐字歌词，请选择要打开的版本。",
                    "This song has both plain and word-by-word lyrics. Choose which version to open."
                ),
                positiveText = null,
                negativeText = null,
                body = {
                    addView(LinearLayout(this@openLocalLyricsTargetPicker).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.END or Gravity.CENTER_VERTICAL
                        setPadding(0, dp(AirUiTokens.Space.Xl), 0, 0)
                        addView(localLyricsDialogButton(tr("普通歌词", "Plain lyrics"), primary = false) {
                            pickerDialog?.dismiss()
                            openLocalLyricsEditor(item, LyricsStorage.LocalLyricsEditTarget.PLAIN, onLyricsSaved)
                        })
                        addView(localLyricsDialogButton(tr("逐字歌词", "Word-by-word lyrics"), primary = true) {
                            pickerDialog?.dismiss()
                            openLocalLyricsEditor(item, LyricsStorage.LocalLyricsEditTarget.KARAOKE, onLyricsSaved)
                        })
                    })
                }
            )
        }
        item.hasKaraokeLyrics && !item.hasPlainLyrics -> {
            openLocalLyricsEditor(item, LyricsStorage.LocalLyricsEditTarget.KARAOKE, onLyricsSaved)
        }
        else -> {
            openLocalLyricsEditor(item, LyricsStorage.LocalLyricsEditTarget.PLAIN, onLyricsSaved)
        }
    }
}

private fun MainActivity.openLocalLyricsEditor(
    item: LyricsStorage.LocalLyricsItem,
    target: LyricsStorage.LocalLyricsEditTarget,
    onLyricsSaved: (() -> Unit)?
) {
    val isKaraoke = target == LyricsStorage.LocalLyricsEditTarget.KARAOKE
    val rawLyrics = LyricsStorage.readLocalLyricsItemText(this, item, target)
    if (rawLyrics == null) {
        showAirDialog(
            title = tr("读取失败", "Read failed"),
            message = if (isKaraoke) {
                tr("无法读取这份逐字歌词。", "Cannot read this word-by-word lyric file.")
            } else {
                tr("无法读取这份歌词。", "Cannot read this lyric file.")
            },
            positiveText = tr("知道了", "OK")
        )
        return
    }

    val editor = EditText(this).apply {
        setText(rawLyrics)
        textSize = AirUiTokens.TextSize.BodySmall
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
        setPadding(dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.Xxl), dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), dp(AirUiTokens.Space.Xxl))
        background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Sm).toFloat()
            setColor(colorSurfaceLight)
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
        }
    }

    var editDialog: android.app.Dialog? = null
    editDialog = showAirDialog(
        title = if (isKaraoke) {
            tr("${item.displayTitle} · 逐字歌词", "${item.displayTitle} · Word-by-word")
        } else {
            item.displayTitle
        },
        message = if (isKaraoke) {
            tr(
                "逐字歌词使用 enhanced LRC：行时间用 [00:12.34]，字词时间用 <00:12.34>。",
                "Word-by-word lyrics use enhanced LRC: line time uses [00:12.34], word time uses <00:12.34>."
            )
        } else null,
        positiveText = null,
        negativeText = null,
        body = {
            addView(editor)
            addView(LinearLayout(this@openLocalLyricsEditor).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(0, dp(AirUiTokens.Space.ButtonH), 0, 0)

                addView(localLyricsDialogButton(tr("检查格式", "Check format"), primary = false) {
                    if (isKaraoke) {
                        val validation = LyricsStorage.validateKaraokeLyricsItemText(editor.text.toString())
                        if (validation.saved) {
                            showAirDialog(
                                title = tr("格式正确", "Format looks good"),
                                message = tr("这份 enhanced LRC 可以保存。", "This enhanced LRC can be saved."),
                                positiveText = tr("知道了", "OK")
                            )
                        } else if (validation.invalidLineNumbers.isNotEmpty()) {
                            showEnhancedLyricsFormatErrorDialog(validation.invalidLineNumbers)
                        } else {
                            showEnhancedLyricsFormatErrorDialog(emptyList())
                        }
                    } else {
                        val validation = LrcParser.validateForStorage(editor.text.toString())
                        if (validation.isValid) {
                            showAirDialog(
                                title = tr("格式正确", "Format looks good"),
                                message = tr("这份普通 LRC 可以保存。", "This plain LRC can be saved."),
                                positiveText = tr("知道了", "OK")
                            )
                        } else {
                            showLyricsFormatErrorDialog(validation.invalidLineNumbers)
                        }
                    }
                })

                addView(localLyricsDialogButton(tr("关闭", "Close"), primary = false) {
                    editDialog?.dismiss()
                })

                addView(localLyricsDialogButton(tr("保存修改", "Save changes"), primary = true) {
                    val result = if (isKaraoke) {
                        LyricsStorage.updateKaraokeLyricsItemTextWithResult(this@openLocalLyricsEditor, item, editor.text.toString())
                    } else {
                        LyricsStorage.updateLocalLyricsItemTextWithResult(this@openLocalLyricsEditor, item, editor.text.toString())
                    }
                    when {
                        result.saved -> {
                            editDialog?.dismiss()
                            reloadFloatingLyrics()
                            onLyricsSaved?.invoke()
                        }
                        result.invalidLineNumbers.isNotEmpty() -> {
                            if (isKaraoke) showEnhancedLyricsFormatErrorDialog(result.invalidLineNumbers) else showLyricsFormatErrorDialog(result.invalidLineNumbers)
                        }
                        else -> {
                            if (isKaraoke) {
                                showEnhancedLyricsFormatErrorDialog(emptyList())
                            } else {
                                showAirDialog(
                                    title = tr("保存失败", "Save failed"),
                                    message = tr(
                                        "请确认内容是普通 LRC，并使用 [00:12.34]歌词 格式。",
                                        "Make sure the content is plain LRC and uses [00:12.34]lyric format."
                                    ),
                                    positiveText = tr("知道了", "OK")
                                )
                            }
                        }
                    }
                })
            })
        }
    )
}

private fun MainActivity.showLyricsFormatErrorDialog(invalidLineNumbers: List<Int>) {
    val message = if (invalidLineNumbers.isNotEmpty()) {
        val lines = invalidLineNumbers.take(8).joinToString("、")
        val suffix = if (invalidLineNumbers.size > 8) "…" else ""
        tr(
            "第 ${lines}${suffix} 行格式不正确。\n\n普通歌词需要使用 [00:12.34]歌词 格式；空行和 [ar:歌手] 这类 LRC 信息行可以保留。",
            "Line ${lines}${suffix} has an invalid format.\n\nPlain lyrics must use the [00:12.34]lyric format. Blank lines and LRC metadata such as [ar:artist] are allowed."
        )
    } else {
        tr(
            "没有找到可保存的有效歌词。请至少保留一行 [00:12.34]歌词。",
            "No valid lyric line was found. Keep at least one [00:12.34]lyric line."
        )
    }

    showAirDialog(
        title = tr("格式不正确", "Invalid format"),
        message = message,
        positiveText = tr("回去修改", "Back to edit")
    )
}

private fun MainActivity.showEnhancedLyricsFormatErrorDialog(invalidLineNumbers: List<Int>) {
    val message = if (invalidLineNumbers.isNotEmpty()) {
        val lines = invalidLineNumbers.take(8).joinToString("、")
        val suffix = if (invalidLineNumbers.size > 8) "…" else ""
        tr(
            "第 ${lines}${suffix} 行格式不正确。\n\n逐字歌词需要使用 enhanced LRC 格式：行时间为 [00:12.34]，每个字词时间为 <00:12.34>歌词。",
            "Line ${lines}${suffix} has an invalid format.\n\nWord-by-word lyrics must use enhanced LRC: line time is [00:12.34], and each word token uses <00:12.34>lyric."
        )
    } else {
        tr(
            "没有找到可保存的逐字歌词。请至少保留一行 [00:12.34]<00:12.34>歌词。",
            "No valid word-by-word lyric line was found. Keep at least one [00:12.34]<00:12.34>lyric line."
        )
    }

    showAirDialog(
        title = tr("格式不正确", "Invalid format"),
        message = message,
        positiveText = tr("回去修改", "Back to edit")
    )
}

private fun MainActivity.localLyricsDialogButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit
): TextView {
    return TextView(this).apply {
        this.text = localizeText(text)
        textSize = AirUiTokens.TextSize.Body
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(if (primary) Color.WHITE else colorTextStrong)
        setPadding(dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Lg), dp(AirUiTokens.Space.Xxl), dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Lg), dp(AirUiTokens.Space.Xxl))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(AirUiTokens.Space.Xl), 0, 0, 0)
        }
        background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Pill).toFloat()
            if (primary) {
                setColor(colorAccent)
            } else {
                setColor(colorSurfaceLight)
                setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
            }
        }
        enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
        setOnClickListener { onClick() }
    }
}

internal fun MainActivity.changelogItem(title: String, body: String): View {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(AirUiTokens.Space.Xxl), 0, dp(AirUiTokens.Space.Xxs))
        addView(TextView(activity).apply {
            text = localizeText(title)
            textSize = AirUiTokens.TextSize.Button
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
        })
        addView(TextView(activity).apply {
            text = localizeText(body)
            textSize = AirUiTokens.TextSize.BodySmall
            setTextColor(colorTextMuted)
            setPadding(0, dp(AirUiTokens.Space.Xs), 0, 0)
        })
    }
}

internal fun MainActivity.permissionSummary(): String {
    val opened = listOf(
        Settings.canDrawOverlays(this),
        hasNotificationPermission(),
        hasNotificationListenerAccess()
    ).count { it }
    return if (com.andsi.airlyrics.i18n.AirLocalizer.isChinese(this)) "已开启 $opened / 3 项基础权限" else "$opened / 3 basic permissions enabled"
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
