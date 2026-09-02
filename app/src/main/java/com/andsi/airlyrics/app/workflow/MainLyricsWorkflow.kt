package com.andsi.airlyrics.app.workflow

import android.app.Dialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.MainGraph
import com.andsi.airlyrics.app.state.LyricsImportType
import com.andsi.airlyrics.app.state.PendingLyricsOverwrite
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.i18n.localizedAssetText
import com.andsi.airlyrics.i18n.localizedOffsetDescription
import com.andsi.airlyrics.media.displayText
import com.andsi.airlyrics.media.toSongIdentity
import com.andsi.airlyrics.settings.store.LyricsOffsetStore
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.playTinyPulse
import com.andsi.airlyrics.ui.components.showAirDialog
import com.andsi.airlyrics.ui.components.showAirConfirmDialog
import com.andsi.airlyrics.ui.components.showAirInfoDialog
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorOnAccent
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong

/** Activity-bound presentation for the retained lyrics workflow state. */
internal class MainLyricsWorkflow(
    private val graph: MainGraph
) {
    private val activity
        get() = graph.activity
    private val state
        get() = graph.state
    private val uiHost
        get() = graph.uiHost
    private val feedback
        get() = graph.feedback

    fun restorePendingOverwriteConfirmation() {
        state.pendingLyricsOverwrite?.let(::showOverwriteConfirmation)
    }

    fun showOverwriteConfirmation(request: PendingLyricsOverwrite) {
        val importAsWordByWord = request.type == LyricsImportType.WORD_BY_WORD
        val overwriteMessage = request.target.displayText + "\n\n" + activity.getString(
            if (importAsWordByWord) {
                R.string.ui_word_by_word_overwrite_message
            } else {
                R.string.ui_overwrite_plain_lyrics_msg
            }
        )
        uiHost.showAirConfirmDialog(
            title = activity.getString(
                if (importAsWordByWord) {
                    R.string.ui_overwrite_local_word_by_word_lyrics
                } else {
                    R.string.ui_overwrite_plain_lyrics
                }
            ),
            message = overwriteMessage,
            positiveText = activity.getString(R.string.ui_overwrite),
            onPositive = {
                if (!activity.isDestroyed) graph.viewModel.confirmLyricsOverwrite(request)
            },
            onNegative = {
                if (!activity.isDestroyed) graph.viewModel.clearPendingLyricsOverwrite(request)
            }
        )
    }

    fun handleLyricsDirectorySelected(uri: Uri) {
        val permissionGranted = runCatching {
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.isSuccess

        if (!permissionGranted) {
            feedback.showError(R.string.ui_lyrics_folder_permission_failed)
            return
        }

        graph.viewModel.setLyricsDirectory(uri)
    }

    fun currentLyricsOffsetSummary(): String {
        val media = graph.viewModel.currentMediaInfo()
            ?: return activity.getString(R.string.ui_waiting_for_current_song)
        return activity.localizedOffsetDescription(
            LyricsOffsetStore.getOffsetMs(activity, media.toSongIdentity())
        )
    }

    fun adjustLyricsOffsetForCurrentMedia(deltaMs: Long): Long? {
        val media = graph.viewModel.currentMediaInfo() ?: return null
        val offset = LyricsOffsetStore.adjustOffsetMs(
            activity,
            media.toSongIdentity(),
            deltaMs
        )
        graph.floatingController.applyLyricsOffset(offset)
        return offset
    }

    fun resetLyricsOffsetForCurrentMedia(): Boolean {
        val media = graph.viewModel.currentMediaInfo() ?: return false
        LyricsOffsetStore.resetOffset(activity, media.toSongIdentity())
        graph.floatingController.applyLyricsOffset(0L)
        return true
    }

    fun showImportLyricsDialog(
        target: SongIdentity,
        plainImportEnabled: Boolean,
        wordByWordImportEnabled: Boolean
    ) {
        lateinit var dialog: Dialog

        fun launchImport(type: LyricsImportType) {
            graph.viewModel.beginLyricsImport(target, type)
            dialog.dismiss()
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                uiHost.dp(AirUiTokens.Space.PageH),
                uiHost.dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs),
                uiHost.dp(AirUiTokens.Space.PageH),
                uiHost.dp(AirUiTokens.Space.Sm)
            )

            addView(TextView(activity).apply {
                text = target.displayText
                textSize = AirUiTokens.TextSize.Button
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(uiHost.colorTextStrong)
                setPadding(0, 0, 0, uiHost.dp(AirUiTokens.Space.Xl))
            })

            addView(importLyricsChoiceRow(
                title = activity.getString(R.string.ui_plain_lyrics_lrc),
                subtitle = if (plainImportEnabled) {
                    null
                } else {
                    activity.getString(R.string.ui_plain_lrc_blocked_by_word_by_word)
                },
                primary = true,
                rowEnabled = plainImportEnabled
            ) { launchImport(LyricsImportType.PLAIN) })

            addView(importLyricsChoiceRow(
                title = activity.getString(R.string.ui_word_by_word_lyrics_lrc_file),
                subtitle = if (wordByWordImportEnabled) {
                    null
                } else {
                    activity.getString(R.string.ui_word_by_word_blocked_by_plain_lrc)
                },
                primary = false,
                rowEnabled = wordByWordImportEnabled
            ) { launchImport(LyricsImportType.WORD_BY_WORD) })

            addView(importLyricsChoiceRow(
                title = activity.getString(R.string.ui_lyrics_format_guide),
                subtitle = activity.getString(R.string.ui_view_lrc_examples_hint),
                primary = false
            ) { showLyricsFormatGuideDialog() })
        }

        dialog = uiHost.showAirDialog(
            title = activity.getString(R.string.ui_choose_import_type),
            positiveText = null,
            negativeText = activity.getString(R.string.ui_cancel),
            body = { addView(content) }
        )
    }

    private fun importLyricsChoiceRow(
        title: String,
        subtitle: String?,
        primary: Boolean,
        rowEnabled: Boolean = true,
        onClick: () -> Unit
    ): TextView {
        return TextView(activity).apply {
            text = subtitle?.let {
                activity.getString(R.string.ui_title_subtitle, title, it)
            } ?: title
            textSize = AirUiTokens.TextSize.Button
            typeface = Typeface.DEFAULT_BOLD
            val usePrimary = primary && rowEnabled
            isEnabled = rowEnabled
            alpha = if (rowEnabled) 1f else 0.68f
            setTextColor(
                when {
                    !rowEnabled -> uiHost.colorTextMuted
                    usePrimary -> uiHost.colorOnAccent
                    else -> uiHost.colorTextStrong
                }
            )
            setLineSpacing(uiHost.dp(AirUiTokens.Space.Xxs).toFloat(), 1f)
            setPadding(
                uiHost.dp(AirUiTokens.Space.ButtonH),
                uiHost.dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs),
                uiHost.dp(AirUiTokens.Space.ButtonH),
                uiHost.dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, uiHost.dp(AirUiTokens.Space.Xl), 0, 0)
            }
            background = GradientDrawable().apply {
                cornerRadius = uiHost.dp(AirUiTokens.Radius.Sm).toFloat()
                if (usePrimary) {
                    setColor(uiHost.colorAccent)
                } else {
                    setColor(uiHost.colorSurfaceLight)
                    setStroke(uiHost.dp(AirUiTokens.Stroke.Hairline), uiHost.colorStroke)
                }
            }
            if (rowEnabled) {
                enableSoftPressFeedback(0.97f)
                setOnClickListener {
                    onClick()
                    playTinyPulse(this)
                }
            }
        }
    }

    private fun showLyricsFormatGuideDialog() {
        uiHost.showAirInfoDialog(
            title = activity.getString(R.string.ui_lyrics_format_guide),
            message = activity.localizedAssetText(
                baseName = "help/lyrics_format",
                fallback = activity.getString(R.string.ui_lyrics_format_guide_body)
            )
        )
    }
}
