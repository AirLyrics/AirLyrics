package com.andsi.airlyrics.app.host

import android.app.Dialog
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.interaction.BatchLyricsProblem
import com.andsi.airlyrics.app.interaction.BatchLyricsStage
import com.andsi.airlyrics.ui.components.dismissAirDialogImmediately
import com.andsi.airlyrics.ui.components.normalText
import com.andsi.airlyrics.ui.components.showAirDialog
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal fun MainActivityUiHost.observeBatchLyricsSession() {
    if (batchObserverInstalled) return
    batchObserverInstalled = true
    batchLyricsSession.restore()
    activity.lifecycleScope.launch {
        var dialog: Dialog? = null
        activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
            combine(batchLyricsSession.state, windowGeneration) { state, _ -> state }.collect { state ->
                dialog?.dismissAirDialogImmediately()
                dialog = null
                if (state == null || state.stage in setOf(BatchLyricsStage.PICKING, BatchLyricsStage.EXPORT_PICKING)) return@collect
                val busy = state.stage in setOf(BatchLyricsStage.READING, BatchLyricsStage.RUNNING, BatchLyricsStage.EXPORTING)
                val importable = state.rows.count { it.target != null && it.problem == null }
                val title = when (state.stage) {
                    BatchLyricsStage.DELETE -> activity.resources.getQuantityString(R.plurals.ui_batch_delete_title, state.items.size, state.items.size)
                    BatchLyricsStage.PREVIEW -> getString(R.string.ui_batch_replace)
                    BatchLyricsStage.READING, BatchLyricsStage.RUNNING, BatchLyricsStage.EXPORTING -> getString(R.string.ui_loading)
                    else -> getString(R.string.ui_batch_result)
                }
                val message = when (state.stage) {
                    BatchLyricsStage.PREVIEW -> getString(R.string.ui_batch_preview, importable, state.rows.size - importable)
                    BatchLyricsStage.FINISHED -> if (state.interrupted) getString(R.string.ui_batch_interrupted)
                    else if (state.exportFailed) getString(R.string.ui_batch_export_failed)
                    else if (state.exporting) getString(R.string.ui_batch_export_result, state.completed, state.failures.size) else
                        getString(R.string.ui_batch_result_counts, state.completed, state.failures.size,
                            state.rows.count { it.target == null || it.problem != null })
                    else -> null
                }
                val body: (LinearLayout.() -> Unit)? = when (state.stage) {
                    BatchLyricsStage.PREVIEW -> ({
                        state.rows.forEach { row ->
                            val detail = row.target?.let { target ->
                                getString(R.string.ui_batch_mapping, row.name,
                                    listOf(target.displayTitle, target.artist).filter(String::isNotBlank).joinToString(" — "),
                                    getString(if (row.wordByWord) R.string.ui_batch_word else R.string.ui_batch_plain))
                            } ?: getString(R.string.ui_batch_skipped_file, row.name, getString(when (row.problem) {
                                BatchLyricsProblem.AMBIGUOUS -> R.string.ui_batch_ambiguous
                                BatchLyricsProblem.DUPLICATE -> R.string.ui_batch_duplicate
                                BatchLyricsProblem.INVALID -> R.string.ui_invalid_format
                                BatchLyricsProblem.TOO_LARGE -> R.string.ui_lrc_file_too_large
                                BatchLyricsProblem.READ -> R.string.ui_read_failed
                                else -> R.string.ui_batch_no_match
                            }))
                            addView(normalText(this@observeBatchLyricsSession, detail))
                        }
                    })
                    BatchLyricsStage.FINISHED -> ({
                        state.failures.forEach { failure ->
                            addView(normalText(this@observeBatchLyricsSession, getString(R.string.ui_batch_skipped_file,
                                failure.name, getString(when (failure.problem) {
                                    BatchLyricsProblem.READ -> R.string.ui_read_failed
                                    BatchLyricsProblem.RESTRICTED -> R.string.ui_batch_export_restricted
                                    BatchLyricsProblem.CHANGED -> R.string.ui_batch_changed
                                    BatchLyricsProblem.ROLLBACK -> R.string.ui_batch_rollback_failed
                                    BatchLyricsProblem.DELETE -> R.string.ui_delete_saved_lyrics_failed
                                    else -> R.string.ui_save_failed
                                }))))
                        }
                    })
                    else -> null
                }
                dialog = showAirDialog(
                    title = title,
                    message = message,
                    positiveText = when {
                        busy -> null
                        state.stage == BatchLyricsStage.PREVIEW && importable == 0 -> null
                        state.stage == BatchLyricsStage.PREVIEW -> getString(R.string.ui_batch_replace)
                        state.stage == BatchLyricsStage.DELETE -> getString(R.string.ui_delete)
                        else -> getString(R.string.ui_ok)
                    },
                    negativeText = if (state.stage in setOf(BatchLyricsStage.PREVIEW, BatchLyricsStage.DELETE)) getString(R.string.ui_cancel) else null,
                    body = body,
                    onPositive = {
                        if (state.stage == BatchLyricsStage.FINISHED) batchLyricsSession.dismiss() else batchLyricsSession.confirm()
                    },
                    onNegative = { batchLyricsSession.dismiss() },
                    onUserDismiss = {
                        if (batchLyricsSession.state.value?.stage == state.stage) batchLyricsSession.dismiss()
                    }
                ).apply { setCancelable(!busy); setCanceledOnTouchOutside(!busy) }
                if (state.stage == BatchLyricsStage.FINISHED) {
                    rebuildCurrentPage(animateContent = false, animateTabs = false)
                }
            }
        }
    }
}
