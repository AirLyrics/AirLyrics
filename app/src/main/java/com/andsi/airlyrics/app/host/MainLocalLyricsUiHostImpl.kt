package com.andsi.airlyrics.app.host

import android.app.Dialog
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import com.andsi.airlyrics.app.interaction.EditorSession
import com.andsi.airlyrics.app.interaction.EditorNotice
import android.view.View
import com.andsi.airlyrics.R
import com.andsi.airlyrics.lyrics.importer.wordByWordLyricsFormatErrorMessage
import com.andsi.airlyrics.lyrics.importer.plainLyricsFormatErrorMessage
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.ui.components.showAirDialog
import com.andsi.airlyrics.ui.components.showAirInfoDialog
import com.andsi.airlyrics.ui.state.restoreOperationConfirmation
import com.andsi.airlyrics.ui.model.LocalLyricsUiItem
import com.andsi.airlyrics.ui.model.LocalLyricsUiChange
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage


internal fun MainActivityUiHost.localLyricsRowImpl(
    item: LocalLyricsUiItem,
    onLyricsChanged: ((LocalLyricsUiChange) -> Unit)? = null,
    badgeText: CharSequence? = null,
    onClickIntercept: (() -> Boolean)? = null
): View {
    return com.andsi.airlyrics.ui.components.LocalLyricsRowView(
        this, { current -> openLocalLyricsEditorForItem(current, onLyricsChanged) }, onClickIntercept, badgeText
    ).apply { bind(item) }
}

private fun MainActivityUiHost.openLocalLyricsEditorForItem(
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

private fun MainActivityUiHost.openLocalLyricsEditor(
    item: LocalLyricsUiItem,
    target: LyricsStorage.LocalLyricsEditTarget,
    onLyricsChanged: ((LocalLyricsUiChange) -> Unit)?
) {
    editorChangeCallback = onLyricsChanged
    observeEditorSession()
    editorSession.open(item, target)
}

internal fun MainActivityUiHost.observeEditorSession() {
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
                    if (editorChangeCallback != null && savedLyricsDeletionPending != null) {
                        savedLyricsDeletionPending = null
                        lyricsSettingsContentRefresh?.invoke()
                    }
                    return@collect
                }
                if (session.finished != null) {
                    binding?.dialog?.dismiss()
                    binding = null
                    editorSession.acknowledgeCompletion()
                    if (session.finished == LocalLyricsUiChange.SAVED) showMessage(R.string.ui_saved)
                    val keepSavedList = session.finished == LocalLyricsUiChange.DELETED &&
                        editorChangeCallback != null && currentPage == Page.SETTINGS &&
                        settingsSubPage == SettingsSubPage.SAVED_LYRICS && savedLyricsDeletionRefresh != null
                    savedLyricsDeletionPending = null
                    editorChangeCallback?.invoke(session.finished)
                    editorChangeCallback = null
                    if (!keepSavedList) rebuildCurrentPage(animateContent = false, animateTabs = false)
                    return@collect
                }
                if (session.notice == EditorNotice.READ_FAILED && interactions.read("editor") == null) {
                    editorSession.cancel()
                    showAirInfoDialog(getString(R.string.ui_read_failed), getString(R.string.ui_cannot_read_this_lyric_file))
                    return@collect
                }
                if (binding?.id != session.id || binding?.dialog?.isShowing != true) {
                    binding?.dialog?.dismiss()
                    binding = showLocalLyricsEditorSheet(session)
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
                        EditorNotice.DELETE_FAILED -> {
                            if (savedLyricsDeletionPending != null) {
                                savedLyricsDeletionPending = null
                                lyricsSettingsContentRefresh?.invoke()
                            }
                            showAirDialog(title = getString(R.string.ui_save_failed))
                        }
                        EditorNotice.SAVE_FAILED -> showAirDialog(title = getString(R.string.ui_save_failed))
                    }
                }
            }
        }
    }
}

internal data class EditorDialogBinding(val id: String, val dialog: Dialog, val setBusy: (Boolean) -> Unit)

private fun MainActivityUiHost.showLyricsFormatErrorDialog(invalidLineNumbers: List<Int>) {
    showAirDialog(
        title = getString(R.string.ui_invalid_format),
        message = plainLyricsFormatErrorMessage(invalidLineNumbers),
        positiveText = getString(R.string.ui_back_to_edit)
    )
}

private fun MainActivityUiHost.showWordByWordLyricsFormatErrorDialog(invalidLineNumbers: List<Int>) {
    showAirDialog(
        title = getString(R.string.ui_invalid_format),
        message = wordByWordLyricsFormatErrorMessage(invalidLineNumbers),
        positiveText = getString(R.string.ui_back_to_edit)
    )
}
