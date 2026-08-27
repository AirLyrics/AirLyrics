package com.andsi.airlyrics.app.host

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.lyrics.importer.wordByWordLyricsFormatErrorMessage
import com.andsi.airlyrics.lyrics.importer.plainLyricsFormatErrorMessage
import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.settings.AirToast
import com.andsi.airlyrics.ui.async.LatestUiTaskRunner
import com.andsi.airlyrics.ui.components.airIconView
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.showAirDialog
import com.andsi.airlyrics.ui.components.showAirConfirmDialog
import com.andsi.airlyrics.ui.model.LocalLyricsUiItem
import com.andsi.airlyrics.ui.model.LocalLyricsUiChange
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentMint
import com.andsi.airlyrics.ui.theme.colorDanger
import com.andsi.airlyrics.ui.theme.colorOnAccent
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong

private val localLyricsEditorLoadRunner = LatestUiTaskRunner()

internal fun MainUiHost.localLyricsRowImpl(
    item: LocalLyricsUiItem,
    onLyricsChanged: ((LocalLyricsUiChange) -> Unit)? = null,
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
                text = badgeText
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
            text = getString(
                R.string.ui_local_lyrics_subtitle_type,
                item.subtitle,
                item.typeText
            )
            textSize = AirUiTokens.TextSize.Caption
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAccentMint)
            setPadding(0, dp(AirUiTokens.Space.Sm), 0, 0)
        })
        addView(TextView(activity).apply {
            text = item.metaText
            textSize = AirUiTokens.TextSize.Caption
            setTextColor(colorTextMuted)
            setPadding(0, dp(AirUiTokens.Space.Xxs), 0, 0)
        })
        setOnClickListener {
            activity.openLocalLyricsEditorForItem(item, onLyricsChanged)
        }
    }
}

private fun MainUiHost.openLocalLyricsEditorForItem(
    item: LocalLyricsUiItem,
    onLyricsChanged: ((LocalLyricsUiChange) -> Unit)?
) {
    when {
        item.hasWordByWordLyrics -> {
            openLocalLyricsEditor(item, LyricsStorage.LocalLyricsEditTarget.WORD_BY_WORD, onLyricsChanged)
        }
        else -> {
            openLocalLyricsEditor(item, LyricsStorage.LocalLyricsEditTarget.PLAIN, onLyricsChanged)
        }
    }
}

private fun MainUiHost.openLocalLyricsEditor(
    item: LocalLyricsUiItem,
    target: LyricsStorage.LocalLyricsEditTarget,
    onLyricsChanged: ((LocalLyricsUiChange) -> Unit)?
) {
    val isWordByWord = target == LyricsStorage.LocalLyricsEditTarget.WORD_BY_WORD
    val storageItem = item.toStorageItem()
    AirToast.showShort(this, R.string.ui_loading)

    localLyricsEditorLoadRunner.submit(
        runtime = this,
        load = { LyricsStorage.readLocalLyricsItemText(this, storageItem, target) }
    ) { rawLyrics ->
        if (rawLyrics == null) {
            showAirDialog(
                title = getString(R.string.ui_read_failed),
                message = getString(
                    if (isWordByWord) {
                        R.string.ui_cannot_read_word_by_word_lyrics_file
                    } else {
                        R.string.ui_cannot_read_this_lyric_file
                    }
                ),
                positiveText = getString(R.string.ui_ok)
            )
        } else {
            showLocalLyricsEditorDialog(item, target, rawLyrics, onLyricsChanged)
        }
    }
}

private fun MainUiHost.showLocalLyricsEditorDialog(
    item: LocalLyricsUiItem,
    target: LyricsStorage.LocalLyricsEditTarget,
    rawLyrics: String,
    onLyricsChanged: ((LocalLyricsUiChange) -> Unit)?
) {
    val isWordByWord = target == LyricsStorage.LocalLyricsEditTarget.WORD_BY_WORD
    val storageItem = item.toStorageItem()
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
    var isBusy = false
    var checkButton: TextView? = null
    var cancelButton: TextView? = null
    var saveButton: TextView? = null
    var deleteButton: View? = null
    fun setBusyState(busy: Boolean) {
        isBusy = busy
        editor.isEnabled = !busy
        listOf(checkButton, cancelButton, saveButton, deleteButton).forEach { action ->
            action?.isEnabled = !busy
            action?.alpha = if (busy) 0.55f else 1f
        }
        editDialog?.setCancelable(!busy)
    }

    val deleteHeaderAction: (LinearLayout.() -> Unit)? = if (item.canDelete) {
        {
            val createdDeleteButton = airIconView(
                iconRes = R.drawable.ic_air_delete,
                tint = colorDanger,
                contentDescription = getString(
                    R.string.ui_delete_saved_lyrics_action,
                    item.displayTitle
                )
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    dp(AirUiTokens.Layout.IconTouchSize),
                    dp(AirUiTokens.Layout.IconTouchSize)
                ).apply {
                    setMargins(dp(AirUiTokens.Space.Xl), 0, 0, 0)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorSurfaceLight)
                }
                isFocusable = true
                enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
                setOnClickListener {
                    if (isBusy) return@setOnClickListener
                    showAirConfirmDialog(
                        title = getString(R.string.ui_delete_saved_lyrics_confirm, item.displayTitle),
                        message = getString(R.string.ui_delete_all_saved_lyrics_message),
                        positiveText = getString(R.string.ui_delete)
                    ) {
                        setBusyState(true)
                        uiActions.deleteSavedLyrics(item) { deleted ->
                            if (deleted) {
                                editDialog?.dismiss()
                                onLyricsChanged?.invoke(LocalLyricsUiChange.DELETED)
                            } else {
                                setBusyState(false)
                            }
                        }
                    }
                }
            }
            deleteButton = createdDeleteButton
            addView(createdDeleteButton)
        }
    } else {
        null
    }

    editDialog = showAirDialog(
        title = if (isWordByWord) {
            getString(R.string.ui_item_title_word_by_word_lyrics, item.displayTitle)
        } else {
            item.displayTitle
        },
        message = if (isWordByWord) {
            getString(R.string.ui_word_by_word_lyrics_format_hint)
        } else null,
        positiveText = null,
        negativeText = null,
        headerAction = deleteHeaderAction,
        body = {
            addView(editor)
            addView(LinearLayout(this@showLocalLyricsEditorDialog).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(AirUiTokens.Space.ButtonH), 0, 0)

                val createdCheckButton = localLyricsDialogButton(
                    text = getString(R.string.ui_check_format),
                    style = LocalLyricsDialogActionStyle.ACCENT_TEXT
                ) {
                    if (isBusy) return@localLyricsDialogButton
                    if (isWordByWord) {
                        val validation = LyricsStorage.validateWordByWordLyricsItemText(editor.text.toString())
                        if (validation.saved) {
                            showAirDialog(
                                title = getString(R.string.ui_format_looks_good),
                                message = null,
                                positiveText = getString(R.string.ui_ok)
                            )
                        } else if (validation.invalidLineNumbers.isNotEmpty()) {
                            showWordByWordLyricsFormatErrorDialog(validation.invalidLineNumbers)
                        } else {
                            showWordByWordLyricsFormatErrorDialog(emptyList())
                        }
                    } else {
                        val validation = LrcParser.validateForStorage(editor.text.toString())
                        if (validation.isValid) {
                            showAirDialog(
                                title = getString(R.string.ui_format_looks_good),
                                message = null,
                                positiveText = getString(R.string.ui_ok)
                            )
                        } else {
                            showLyricsFormatErrorDialog(validation.invalidLineNumbers)
                        }
                    }
                }
                checkButton = createdCheckButton
                addView(createdCheckButton)

                addView(LinearLayout(this@showLocalLyricsEditorDialog).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    setPadding(0, dp(AirUiTokens.Space.Sm), 0, 0)

                    val createdCancelButton = localLyricsDialogButton(
                        text = getString(R.string.ui_cancel),
                        style = LocalLyricsDialogActionStyle.TEXT
                    ) {
                        if (isBusy) return@localLyricsDialogButton
                        editDialog?.dismiss()
                    }
                    cancelButton = createdCancelButton
                    addView(createdCancelButton)

                    val createdSaveButton = localLyricsDialogButton(
                        text = getString(R.string.ui_save_changes),
                        style = LocalLyricsDialogActionStyle.PRIMARY,
                        marginStartDp = AirUiTokens.Space.Lg
                    ) {
                        if (isBusy) return@localLyricsDialogButton
                        val newText = editor.text.toString()
                        setBusyState(true)
                        AirToast.showShort(this@showLocalLyricsEditorDialog, R.string.ui_saving)
                        runOnAppIo {
                            val result = if (isWordByWord) {
                                LyricsStorage.updateWordByWordLyricsItemTextWithResult(this@showLocalLyricsEditorDialog, storageItem, newText)
                            } else {
                                LyricsStorage.updatePlainLyricsItemTextWithResult(this@showLocalLyricsEditorDialog, storageItem, newText)
                            }
                            runOnMainThread {
                                when {
                                    result.saved -> {
                                        editDialog?.dismiss()
                                        uiActions.reloadFloatingLyrics()
                                        onLyricsChanged?.invoke(LocalLyricsUiChange.SAVED)
                                    }
                                    result.invalidLineNumbers.isNotEmpty() -> {
                                        setBusyState(false)
                                        if (isWordByWord) showWordByWordLyricsFormatErrorDialog(result.invalidLineNumbers) else showLyricsFormatErrorDialog(result.invalidLineNumbers)
                                    }
                                    else -> {
                                        setBusyState(false)
                                        if (isWordByWord) {
                                            showWordByWordLyricsFormatErrorDialog(emptyList())
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
                    saveButton = createdSaveButton
                    addView(createdSaveButton)
                })
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

private fun MainUiHost.showWordByWordLyricsFormatErrorDialog(invalidLineNumbers: List<Int>) {
    showAirDialog(
        title = getString(R.string.ui_invalid_format),
        message = wordByWordLyricsFormatErrorMessage(invalidLineNumbers),
        positiveText = getString(R.string.ui_back_to_edit)
    )
}

private enum class LocalLyricsDialogActionStyle {
    ACCENT_TEXT,
    TEXT,
    PRIMARY
}

private fun MainUiHost.localLyricsDialogButton(
    text: String,
    style: LocalLyricsDialogActionStyle,
    marginStartDp: Int = 0,
    onClick: () -> Unit
): TextView {
    return TextView(this).apply {
        this.text = text
        textSize = AirUiTokens.TextSize.Button
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(
            when (style) {
                LocalLyricsDialogActionStyle.ACCENT_TEXT -> colorAccent
                LocalLyricsDialogActionStyle.TEXT -> colorTextStrong
                LocalLyricsDialogActionStyle.PRIMARY -> colorOnAccent
            }
        )
        val horizontalPadding = when (style) {
            LocalLyricsDialogActionStyle.ACCENT_TEXT -> AirUiTokens.Space.Sm
            LocalLyricsDialogActionStyle.TEXT -> AirUiTokens.Space.Xl
            LocalLyricsDialogActionStyle.PRIMARY -> AirUiTokens.Space.Xl + AirUiTokens.Space.Lg
        }
        setPadding(
            dp(horizontalPadding),
            dp(AirUiTokens.Space.Xxl),
            dp(horizontalPadding),
            dp(AirUiTokens.Space.Xxl)
        )
        minHeight = dp(AirUiTokens.Layout.IconTouchSize)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(marginStartDp), 0, 0, 0)
        }
        background = if (style == LocalLyricsDialogActionStyle.PRIMARY) {
            GradientDrawable().apply {
                cornerRadius = dp(AirUiTokens.Radius.Pill).toFloat()
                setColor(colorAccent)
            }
        } else {
            null
        }
        enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
        setOnClickListener { onClick() }
    }
}
