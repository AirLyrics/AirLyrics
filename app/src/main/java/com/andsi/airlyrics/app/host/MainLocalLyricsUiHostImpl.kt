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
import com.andsi.airlyrics.lyrics.importer.wordByWordLyricsFormatErrorMessage
import com.andsi.airlyrics.lyrics.importer.plainLyricsFormatErrorMessage
import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.settings.AirToast
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.showAirDialog
import com.andsi.airlyrics.ui.async.LatestUiTaskRunner
import com.andsi.airlyrics.ui.model.LocalLyricsUiItem
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentMint
import com.andsi.airlyrics.ui.theme.colorOnAccent
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import com.andsi.airlyrics.design.tokens.AirUiTokens

private val localLyricsEditorLoadRunner = LatestUiTaskRunner()

internal fun MainUiHost.localLyricsRowImpl(
    item: LocalLyricsUiItem,
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
            activity.openLocalLyricsEditorForItem(item, onLyricsSaved)
        }
    }
}

private fun MainUiHost.openLocalLyricsEditorForItem(
    item: LocalLyricsUiItem,
    onLyricsSaved: (() -> Unit)?
) {
    when {
        item.hasWordByWordLyrics -> {
            openLocalLyricsEditor(item.toStorageItem(), LyricsStorage.LocalLyricsEditTarget.WORD_BY_WORD, onLyricsSaved)
        }
        else -> {
            openLocalLyricsEditor(item.toStorageItem(), LyricsStorage.LocalLyricsEditTarget.PLAIN, onLyricsSaved)
        }
    }
}

private fun MainUiHost.openLocalLyricsEditor(
    item: LyricsStorage.LocalLyricsItem,
    target: LyricsStorage.LocalLyricsEditTarget,
    onLyricsSaved: (() -> Unit)?
) {
    val isWordByWord = target == LyricsStorage.LocalLyricsEditTarget.WORD_BY_WORD
    AirToast.showShort(this, R.string.ui_loading)

    localLyricsEditorLoadRunner.submit(
        runtime = this,
        load = { LyricsStorage.readLocalLyricsItemText(this, item, target) }
    ) { rawLyrics ->
        if (rawLyrics == null) {
            showAirDialog(
                title = getString(R.string.ui_read_failed),
                message = if (isWordByWord) {
                    getString(R.string.ui_cannot_read_word_by_word_lyrics_file)
                } else {
                    getString(R.string.ui_cannot_read_this_lyric_file)
                },
                positiveText = getString(R.string.ui_ok)
            )
        } else {
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
    val isWordByWord = target == LyricsStorage.LocalLyricsEditTarget.WORD_BY_WORD
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
        body = {
            addView(editor)
            addView(LinearLayout(this@showLocalLyricsEditorDialog).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(0, dp(AirUiTokens.Space.ButtonH), 0, 0)

                addView(localLyricsDialogButton(getString(R.string.ui_check_format), primary = false) {
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
                })

                addView(localLyricsDialogButton(getString(R.string.ui_close), primary = false) {
                    editDialog?.dismiss()
                })

                saveButton = localLyricsDialogButton(getString(R.string.ui_save_changes), primary = true) {
                    if (isSaving) return@localLyricsDialogButton
                    val newText = editor.text.toString()
                    setSavingState(true)
                    AirToast.showShort(this@showLocalLyricsEditorDialog, R.string.ui_saving)
                    runOnAppIo {
                        val result = if (isWordByWord) {
                            LyricsStorage.updateWordByWordLyricsItemTextWithResult(this@showLocalLyricsEditorDialog, item, newText)
                        } else {
                            LyricsStorage.updatePlainLyricsItemTextWithResult(this@showLocalLyricsEditorDialog, item, newText)
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
                                    if (isWordByWord) showWordByWordLyricsFormatErrorDialog(result.invalidLineNumbers) else showLyricsFormatErrorDialog(result.invalidLineNumbers)
                                }
                                else -> {
                                    setSavingState(false)
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

private fun MainUiHost.showWordByWordLyricsFormatErrorDialog(invalidLineNumbers: List<Int>) {
    showAirDialog(
        title = getString(R.string.ui_invalid_format),
        message = wordByWordLyricsFormatErrorMessage(invalidLineNumbers),
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
        setTextColor(if (primary) colorOnAccent else colorTextStrong)
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
