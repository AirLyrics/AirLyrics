package com.andsi.airlyrics.app.host

import android.app.Dialog
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.core.widget.doAfterTextChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import com.andsi.airlyrics.app.interaction.EditorSession
import com.andsi.airlyrics.app.interaction.EditorNotice
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatEditText
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.ui.layout.WindowLayoutSpec
import com.andsi.airlyrics.ui.components.AdaptiveGridLayout
import com.andsi.airlyrics.ui.components.expandableText
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.lyrics.importer.wordByWordLyricsFormatErrorMessage
import com.andsi.airlyrics.lyrics.importer.plainLyricsFormatErrorMessage
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.ui.components.airIconView
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.showAirDialog
import com.andsi.airlyrics.ui.components.showAirInfoDialog
import com.andsi.airlyrics.ui.state.confirmOperation
import com.andsi.airlyrics.ui.state.restoreOperationConfirmation
import com.andsi.airlyrics.ui.state.ConfirmationOperation
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


internal fun MainUiHost.localLyricsRowImpl(
    item: LocalLyricsUiItem,
    onLyricsChanged: ((LocalLyricsUiChange) -> Unit)? = null,
    badgeText: CharSequence? = null
): View {
    val activity = this
    return LinearLayout(this).apply {
        setTag(R.id.interaction_anchor, "lyrics:${item.indexKey.ifBlank { item.name }}")
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
        addView(expandableText(TextView(activity).apply {
            text = item.displayTitle
            textSize = AirUiTokens.TextSize.Body
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
        }))
        addView(expandableText(TextView(activity).apply {
            text = getString(
                R.string.ui_local_lyrics_subtitle_type,
                item.subtitle,
                item.typeText
            )
            textSize = AirUiTokens.TextSize.Caption
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAccentMint)
            setPadding(0, dp(AirUiTokens.Space.Sm), 0, 0)
        }))
        addView(expandableText(TextView(activity).apply {
            text = item.metaText
            textSize = AirUiTokens.TextSize.Caption
            setTextColor(colorTextMuted)
            setPadding(0, dp(AirUiTokens.Space.Xxs), 0, 0)
        }))
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
    editorChangeCallback = onLyricsChanged
    observeEditorSession()
    editorSession.open(item, target)
}

internal fun MainUiHost.observeEditorSession() {
    if (editorObserverInstalled) return
    editorObserverInstalled = true
    editorSession.restore()
    activity.lifecycleScope.launch {
        var binding: EditorDialogBinding? = null
        activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
            combine(editorSession.state, windowGeneration) { session, _ -> session }.collect { session ->
                if (session == null) {
                    binding?.dialog?.dismiss()
                    binding = null
                    return@collect
                }
                if (session.finished != null) {
                    binding?.dialog?.dismiss()
                    binding = null
                    editorSession.acknowledgeCompletion()
                    if (session.finished == LocalLyricsUiChange.SAVED) showMessage(R.string.ui_saved)
                    editorChangeCallback?.invoke(session.finished)
                    editorChangeCallback = null
                    rebuildCurrentPage(animateContent = false, animateTabs = false)
                    return@collect
                }
                if (session.notice == EditorNotice.READ_FAILED && interactions.read("editor") == null) {
                    editorSession.cancel()
                    showAirInfoDialog(getString(R.string.ui_read_failed), getString(R.string.ui_cannot_read_this_lyric_file))
                    return@collect
                }
                if (binding?.id != session.id || binding?.dialog?.isShowing != true) {
                    binding?.dialog?.dismiss()
                    binding = showLocalLyricsEditorDialog(session)
                }
                binding?.setBusy?.invoke(session.busy)
                restoreOperationConfirmation()
                session.notice?.let { notice ->
                    editorSession.consumeNotice()
                    when (notice) {
                        EditorNotice.INVALID -> if (session.target == LyricsStorage.LocalLyricsEditTarget.WORD_BY_WORD) {
                            showWordByWordLyricsFormatErrorDialog(session.invalidLines)
                        } else showLyricsFormatErrorDialog(session.invalidLines)
                        EditorNotice.VALID -> showAirDialog(title = getString(R.string.ui_format_looks_good))
                        EditorNotice.READ_FAILED -> showMessage(R.string.ui_read_failed)
                        EditorNotice.DELETE_FAILED, EditorNotice.SAVE_FAILED -> showAirDialog(title = getString(R.string.ui_save_failed))
                    }
                }
            }
        }
    }
}

private data class EditorDialogBinding(val id: String, val dialog: Dialog, val setBusy: (Boolean) -> Unit)

private fun MainUiHost.showLocalLyricsEditorDialog(session: EditorSession): EditorDialogBinding {
    val item = session.item
    var changed: (() -> Unit)? = null
    val editor = object : AppCompatEditText(this) {
        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            changed?.invoke()
        }
    }.apply {
        setText(session.text)
        textSize = AirUiTokens.TextSize.BodySmall
        minLines = 8
        maxLines = 18
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setHorizontallyScrolling(false)
        setSelection(session.selectionStart.coerceIn(0, length()), session.selectionEnd.coerceIn(0, length()))
        setTextColor(colorTextStrong)
        setHintTextColor(colorTextMuted)
        setPadding(dp(AirUiTokens.Space.Xxl), dp(AirUiTokens.Space.Xxl), dp(AirUiTokens.Space.Xxl), dp(AirUiTokens.Space.Xxl))
        background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Sm).toFloat()
            setColor(colorSurfaceLight)
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
        }
    }
    fun capture() {
        if (editorSession.state.value?.id == session.id) {
            editorSession.edit(editor.text.toString(), editor.selectionStart, editor.selectionEnd, editor.scrollY)
        }
    }
    editor.post {
        editor.scrollTo(0, session.scrollY)
        changed = ::capture
    }
    editor.doAfterTextChanged { capture() }
    editor.setOnScrollChangeListener { _, _, _, _, _ -> capture() }
    interactionUi.snapshot(editor, ::capture)
    editor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) {
            interactionUi.forgetSnapshot(editor)
            changed = null
        }
    })
    val buttons = mutableListOf<View>()
    lateinit var dialog: Dialog
    val deleteHeader: (LinearLayout.() -> Unit)? = if (item.canDelete) {
        {
            val delete = airIconView(R.drawable.ic_air_delete, colorDanger,
                getString(R.string.ui_delete_saved_lyrics_action, item.displayTitle)).apply {
                layoutParams = LinearLayout.LayoutParams(dp(AirUiTokens.Layout.IconTouchSize), dp(AirUiTokens.Layout.IconTouchSize))
                setOnClickListener {
                    confirmOperation(
                        operation = ConfirmationOperation.DELETE_EDITOR,
                        title = getString(R.string.ui_delete_saved_lyrics_confirm, item.displayTitle),
                        message = getString(R.string.ui_delete_all_saved_lyrics_message),
                        positiveText = getString(R.string.ui_delete)
                    )
                }
            }
            buttons += delete
            addView(delete)
        }
    } else null
    dialog = showAirDialog(
        title = if (session.target == LyricsStorage.LocalLyricsEditTarget.WORD_BY_WORD) {
            getString(R.string.ui_item_title_word_by_word_lyrics, item.displayTitle)
        } else item.displayTitle,
        message = if (session.target == LyricsStorage.LocalLyricsEditTarget.WORD_BY_WORD) getString(R.string.ui_word_by_word_lyrics_format_hint) else null,
        positiveText = null,
        maxWidthDp = WindowLayoutSpec.LARGE_DIALOG_MAX,
        headerAction = deleteHeader,
        onUserDismiss = { if (editorSession.state.value?.id == session.id) editorSession.cancel() },
        body = {
            addView(editor)
            addView(LinearLayout(this@showLocalLyricsEditorDialog).apply {
                orientation = LinearLayout.VERTICAL
                val check = localLyricsDialogButton(getString(R.string.ui_check_format), LocalLyricsDialogActionStyle.ACCENT_TEXT) {
                    capture(); editorSession.check()
                }
                buttons += check
                addView(check)
                addView(AdaptiveGridLayout(this@showLocalLyricsEditorDialog, 2,
                    dp(AirUiTokens.Space.Lg), dp(AirUiTokens.Space.Lg), fillCells = false).apply {
                    val cancel = localLyricsDialogButton(getString(R.string.ui_cancel), LocalLyricsDialogActionStyle.TEXT) {
                        editorSession.cancel(); dialog.dismiss()
                    }
                    val save = localLyricsDialogButton(getString(R.string.ui_save_changes), LocalLyricsDialogActionStyle.PRIMARY, AirUiTokens.Space.Lg) {
                        capture(); editorSession.save()
                    }
                    buttons += cancel; buttons += save
                    addView(cancel); addView(save)
                })
            })
        }
    )
    return EditorDialogBinding(session.id, dialog) { busy ->
        editor.isEnabled = !busy
        buttons.forEach { it.isEnabled = !busy; it.alpha = if (busy) 0.55f else 1f }
        dialog.setCancelable(!busy)
    }
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
