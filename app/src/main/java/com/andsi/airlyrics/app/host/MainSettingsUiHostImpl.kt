package com.andsi.airlyrics.app.host

import com.andsi.airlyrics.app.platform.AppNavigator
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.model.MainUiHost

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
import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.lyrics.importer.enhancedLyricsFormatErrorMessage
import com.andsi.airlyrics.lyrics.importer.plainLyricsFormatErrorMessage
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
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
import com.andsi.airlyrics.i18n.localizedLocalLyricsMeta
import com.andsi.airlyrics.i18n.localizedLocalLyricsType
import com.andsi.airlyrics.i18n.localizedLocalLyricsSubtitle
import com.andsi.airlyrics.ui.tokens.AirUiTokens
import java.util.concurrent.atomic.AtomicInteger

private val localLyricsEditorLoadGeneration = AtomicInteger(0)

internal fun MainUiHost.settingsHomeHeaderImpl(): View {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Lg))

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = getString(R.string.ui_settings)
                textSize = AirUiTokens.TextSize.PageTitle
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextStrong)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(activity.themeToggleButton())
        })
    }
}

internal fun MainUiHost.settingsBackHeaderImpl(title: String, subtitle: String = ""): View {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Lg))
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = getString(R.string.ui_settings_back_label)
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
            text = title
            textSize = AirUiTokens.TextSize.PageTitle
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
        })
        if (subtitle.isNotBlank()) {
            addView(TextView(activity).apply {
                text = subtitle
                textSize = AirUiTokens.TextSize.Body
                setTextColor(colorTextMuted)
                setPadding(0, dp(AirUiTokens.Space.Sm), 0, 0)
            })
        }
    }
}

internal fun MainUiHost.themeToggleButtonImpl(): TextView {
    val activity = this
    return TextView(this).apply {
        text = if (isDarkTheme()) "☀" else "☾"
        textSize = AirUiTokens.TextSize.PageTitle - 4f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(colorAccent)
        contentDescription = if (isDarkTheme()) getString(R.string.ui_switch_to_light_mode) else getString(R.string.ui_switch_to_dark_mode)
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

internal fun MainUiHost.settingsCategoryCardImpl(
    title: String,
    subtitle: String,
    status: String,
    accent: Int,
    iconRes: Int,
    onClick: () -> Unit
): View {
    val activity = this
    return card(activity) {
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

internal fun MainUiHost.localLyricsRowImpl(
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
                text = badgeText ?: ""
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


private fun MainUiHost.openLocalLyricsTargetPicker(
    item: LyricsStorage.LocalLyricsItem,
    onLyricsSaved: (() -> Unit)?
) {
    when {
        item.hasPlainLyrics && item.hasKaraokeLyrics -> {
            var pickerDialog: android.app.Dialog? = null
            pickerDialog = showAirDialog(
                title = item.displayTitle,
                message = getString(R.string.ui_choose_lyrics_version_message),
                positiveText = null,
                negativeText = null,
                body = {
                    addView(LinearLayout(this@openLocalLyricsTargetPicker).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.END or Gravity.CENTER_VERTICAL
                        setPadding(0, dp(AirUiTokens.Space.Xl), 0, 0)
                        addView(localLyricsDialogButton(getString(R.string.ui_plain_lyrics), primary = false) {
                            pickerDialog?.dismiss()
                            openLocalLyricsEditor(item, LyricsStorage.LocalLyricsEditTarget.PLAIN, onLyricsSaved)
                        })
                        addView(localLyricsDialogButton(getString(R.string.ui_enhanced_lrc_lyrics), primary = true) {
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

private fun MainUiHost.openLocalLyricsEditor(
    item: LyricsStorage.LocalLyricsItem,
    target: LyricsStorage.LocalLyricsEditTarget,
    onLyricsSaved: (() -> Unit)?
) {
    val isKaraoke = target == LyricsStorage.LocalLyricsEditTarget.KARAOKE
    val loadGeneration = localLyricsEditorLoadGeneration.incrementAndGet()
    Toast.makeText(this, getString(R.string.ui_loading), Toast.LENGTH_SHORT).show()

    runOnAppIo {
        val rawLyrics = LyricsStorage.readLocalLyricsItemText(this, item, target)
        runOnMainThread {
            if (loadGeneration != localLyricsEditorLoadGeneration.get()) return@runOnMainThread

            if (rawLyrics == null) {
                showAirDialog(
                    title = getString(R.string.ui_read_failed),
                    message = if (isKaraoke) {
                        getString(R.string.ui_cannot_read_enhanced_lrc_file)
                    } else {
                        getString(R.string.ui_cannot_read_this_lyric_file)
                    },
                    positiveText = getString(R.string.ui_ok)
                )
                return@runOnMainThread
            }

            showLocalLyricsEditorDialog(item, target, rawLyrics, onLyricsSaved)
        }
    }
}

private fun MainUiHost.showLocalLyricsEditorDialog(
    item: LyricsStorage.LocalLyricsItem,
    target: LyricsStorage.LocalLyricsEditTarget,
    rawLyrics: String,
    onLyricsSaved: (() -> Unit)?
) {
    val isKaraoke = target == LyricsStorage.LocalLyricsEditTarget.KARAOKE
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
    var isSaving = false
    lateinit var saveButton: TextView
    fun setSavingState(saving: Boolean) {
        isSaving = saving
        saveButton.isEnabled = !saving
        saveButton.alpha = if (saving) 0.55f else 1f
    }

    editDialog = showAirDialog(
        title = if (isKaraoke) {
            getString(R.string.ui_item_displaytitle_enhanced_lrc, item.displayTitle)
        } else {
            item.displayTitle
        },
        message = if (isKaraoke) {
            getString(R.string.ui_enhanced_lrc_format_hint)
        } else null,
        positiveText = null,
        negativeText = null,
        body = {
            addView(editor)
            addView(LinearLayout(this@showLocalLyricsEditorDialog).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(0, dp(AirUiTokens.Space.ButtonH), 0, 0)

                addView(localLyricsDialogButton(getString(R.string.ui_check_format), primary = false) {
                    if (isKaraoke) {
                        val validation = LyricsStorage.validateKaraokeLyricsItemText(editor.text.toString())
                        if (validation.saved) {
                            showAirDialog(
                                title = getString(R.string.ui_format_looks_good),
                                message = getString(R.string.ui_this_enhanced_lrc_can_be_saved),
                                positiveText = getString(R.string.ui_ok)
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
                                title = getString(R.string.ui_format_looks_good),
                                message = getString(R.string.ui_this_plain_lrc_can_be_saved),
                                positiveText = getString(R.string.ui_ok)
                            )
                        } else {
                            showLyricsFormatErrorDialog(validation.invalidLineNumbers)
                        }
                    }
                })

                addView(localLyricsDialogButton(getString(R.string.ui_close), primary = false) {
                    editDialog?.dismiss()
                })

                saveButton = localLyricsDialogButton(getString(R.string.ui_save_changes), primary = true) {
                    if (isSaving) return@localLyricsDialogButton
                    val newText = editor.text.toString()
                    setSavingState(true)
                    Toast.makeText(this@showLocalLyricsEditorDialog, getString(R.string.ui_saving), Toast.LENGTH_SHORT).show()
                    runOnAppIo {
                        val result = if (isKaraoke) {
                            LyricsStorage.updateKaraokeLyricsItemTextWithResult(this@showLocalLyricsEditorDialog, item, newText)
                        } else {
                            LyricsStorage.updateLocalLyricsItemTextWithResult(this@showLocalLyricsEditorDialog, item, newText)
                        }
                        runOnMainThread {
                            when {
                                result.saved -> {
                                    editDialog?.dismiss()
                                    uiActions.reloadFloatingLyrics()
                                    onLyricsSaved?.invoke()
                                }
                                result.invalidLineNumbers.isNotEmpty() -> {
                                    setSavingState(false)
                                    if (isKaraoke) showEnhancedLyricsFormatErrorDialog(result.invalidLineNumbers) else showLyricsFormatErrorDialog(result.invalidLineNumbers)
                                }
                                else -> {
                                    setSavingState(false)
                                    if (isKaraoke) {
                                        showEnhancedLyricsFormatErrorDialog(emptyList())
                                    } else {
                                        showAirDialog(
                                            title = getString(R.string.ui_save_failed),
                                            message = getString(R.string.ui_plain_lrc_edit_format_hint),
                                            positiveText = getString(R.string.ui_ok)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                addView(saveButton)
            })
        }
    )
}

private fun MainUiHost.showLyricsFormatErrorDialog(invalidLineNumbers: List<Int>) {
    showAirDialog(
        title = getString(R.string.ui_invalid_format),
        message = plainLyricsFormatErrorMessage(invalidLineNumbers),
        positiveText = getString(R.string.ui_back_to_edit)
    )
}

private fun MainUiHost.showEnhancedLyricsFormatErrorDialog(invalidLineNumbers: List<Int>) {
    showAirDialog(
        title = getString(R.string.ui_invalid_format),
        message = enhancedLyricsFormatErrorMessage(invalidLineNumbers),
        positiveText = getString(R.string.ui_back_to_edit)
    )
}

private fun MainUiHost.localLyricsDialogButton(
    text: String,
    primary: Boolean,
    onClick: () -> Unit
): TextView {
    return TextView(this).apply {
        this.text = text
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

internal fun MainUiHost.changelogItemImpl(title: String, body: String): View {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(AirUiTokens.Space.Xxl), 0, dp(AirUiTokens.Space.Xxs))
        addView(TextView(activity).apply {
            text = title
            textSize = AirUiTokens.TextSize.Button
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
        })
        addView(TextView(activity).apply {
            text = body
            textSize = AirUiTokens.TextSize.BodySmall
            setTextColor(colorTextMuted)
            setPadding(0, dp(AirUiTokens.Space.Xs), 0, 0)
        })
    }
}

internal fun MainUiHost.permissionSummaryImpl(): String {
    val opened = listOf(
        Settings.canDrawOverlays(this),
        hasNotificationPermission(),
        hasNotificationListenerAccess()
    ).count { it }
    return getString(R.string.permissions_summary, opened, 3)
}

internal fun MainUiHost.getAppVersionNameImpl(): String {
    return try {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        packageInfo.versionName ?: "1.0"
    } catch (e: Exception) {
        "1.0"
    }
}

internal fun MainUiHost.openUrlImpl(url: String) {
    AppNavigator.openUrl(this, url)
}


internal fun MainUiHost.refreshAfterLanguageChangedImpl() {
    rebuildMainView()
    refreshCurrentPage()
    uiActions.reloadFloatingLyrics()
}
