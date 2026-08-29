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
import com.andsi.airlyrics.app.state.PendingLyricsImport
import com.andsi.airlyrics.app.state.PendingLyricsOverwrite
import com.andsi.airlyrics.i18n.localizedAssetText
import com.andsi.airlyrics.i18n.localizedOffsetDescription
import com.andsi.airlyrics.lyrics.importer.LyricsImportValidator
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.media.displayText
import com.andsi.airlyrics.media.toSongIdentity
import com.andsi.airlyrics.settings.store.LyricsOffsetStore
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.playTinyPulse
import com.andsi.airlyrics.ui.components.showAirDialog
import com.andsi.airlyrics.ui.components.showAirInfoDialog
import com.andsi.airlyrics.ui.refresh.PageRebuildReason
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorOnAccent
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import com.andsi.airlyrics.design.tokens.AirUiTokens

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

    fun requestOverwriteConfirmation(request: PendingLyricsOverwrite) {
        state.pendingLyricsOverwrite = request
        showOverwriteConfirmation(request)
    }

    fun restorePendingOverwriteConfirmation() {
        state.pendingLyricsOverwrite?.let(::showOverwriteConfirmation)
    }

    private fun showOverwriteConfirmation(request: PendingLyricsOverwrite) {
        graph.lyricsController.showOverwriteConfirmation(
            request = request,
            onPositive = { confirmOverwrite(request) },
            onNegative = { state.clearPendingLyricsOverwrite(request) }
        )
    }

    private fun confirmOverwrite(expected: PendingLyricsOverwrite) {
        if (activity.isDestroyed) return
        val request = state.consumePendingLyricsOverwrite(expected) ?: return
        graph.lyricsController.importLyricsForTarget(
            uri = request.uri,
            target = request.target,
            overwrite = true,
            importAsWordByWord = request.type == LyricsImportType.WORD_BY_WORD
        )
    }

    fun handleLyricsFileResult(uri: Uri?) {
        val request = state.consumePendingLyricsImport()
        if (uri == null) return
        if (request == null) {
            feedback.showError(R.string.ui_import_request_expired)
            return
        }

        val importAsWordByWord = request.type == LyricsImportType.WORD_BY_WORD

        if (!LyricsImportValidator.isLikelyLyricsDocument(activity, uri)) {
            val messageRes = if (importAsWordByWord) {
                R.string.ui_please_choose_a_word_by_word_lrc_file
            } else {
                R.string.ui_please_choose_a_plain_lrc_lyrics_file
            }
            feedback.showError(messageRes)
            return
        }

        if (LyricsImportValidator.isLyricsDocumentTooLarge(activity, uri)) {
            feedback.showError(R.string.ui_lrc_file_too_large)
            return
        }

        graph.lyricsController.importLyricsForTarget(
            uri = uri,
            target = request.target,
            overwrite = false,
            importAsWordByWord = importAsWordByWord
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

        if (!LyricsStorage.validateLyricsDir(activity, uri)) {
            feedback.showError(R.string.ui_lyrics_folder_write_failed)
            return
        }

        LyricsStorage.saveLyricsDirUri(activity, uri)
        feedback.showMessage(R.string.ui_lyrics_save_folder_set)
        graph.uiInvalidator.rebuildCurrentPage(
            reason = PageRebuildReason.LYRICS_DIRECTORY_CHANGED,
            animateContent = false,
            animateTabs = false
        )
    }

    fun currentLyricsOffsetSummary(): String {
        val media = graph.lyricsController.getCurrentMediaInfo()
            ?: return activity.getString(R.string.ui_waiting_for_current_song)
        return activity.localizedOffsetDescription(LyricsOffsetStore.getOffsetMs(activity, media.toSongIdentity()))
    }

    fun adjustLyricsOffsetForCurrentMedia(deltaMs: Long): Long? {
        val media = graph.lyricsController.getCurrentMediaInfo() ?: return null
        val offset = LyricsOffsetStore.adjustOffsetMs(activity, media.toSongIdentity(), deltaMs)
        graph.floatingController.applyLyricsOffset(offset)
        return offset
    }

    fun resetLyricsOffsetForCurrentMedia(): Boolean {
        val media = graph.lyricsController.getCurrentMediaInfo() ?: return false
        LyricsOffsetStore.resetOffset(activity, media.toSongIdentity())
        graph.floatingController.applyLyricsOffset(0L)
        return true
    }

    fun showImportLyricsDialog() {
        val media = graph.lyricsController.getCurrentMediaInfo()
        if (media == null || media.title.isBlank()) {
            feedback.showMessage(R.string.ui_select_song_before_importing)
            return
        }

        val uiGeneration = graph.currentUiGeneration()
        graph.runOnAppIo {
            val localInfo = LyricsStorage.getLocalPlainLyricsInfo(
                context = activity,
                title = media.title,
                artist = media.artist,
                duration = media.durationMs
            )
            val hasWordByWordLyrics = LyricsStorage.hasWordByWordLyrics(
                context = activity,
                title = media.title,
                artist = media.artist,
                duration = media.durationMs
            )
            val plainImportEnabled = !hasWordByWordLyrics
            val wordByWordImportEnabled = localInfo == null ||
                localInfo.plainSource == LyricsStorage.SOURCE_WORD_BY_WORD_FALLBACK

            graph.runOnStartedUi(uiGeneration) {
                var dialog: Dialog? = null

                fun launchImport(asWordByWord: Boolean) {
                    state.pendingLyricsImport = PendingLyricsImport(
                        target = media.toSongIdentity(),
                        type = if (asWordByWord) {
                            LyricsImportType.WORD_BY_WORD
                        } else {
                            LyricsImportType.PLAIN
                        }
                    )
                    dialog?.dismiss()
                    graph.launchers.selectLyricsFile()
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
                        text = media.displayText
                        textSize = AirUiTokens.TextSize.Button
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(uiHost.colorTextStrong)
                        setPadding(0, 0, 0, uiHost.dp(AirUiTokens.Space.Xl))
                    })

                    addView(importLyricsChoiceRow(
                        title = activity.getString(R.string.ui_plain_lyrics_lrc),
                        subtitle = activity.getString(
                            if (plainImportEnabled) {
                                R.string.ui_please_choose_a_plain_lrc_lyrics_file
                            } else {
                                R.string.ui_plain_lrc_blocked_by_word_by_word
                            }
                        ),
                        primary = true,
                        rowEnabled = plainImportEnabled
                    ) { launchImport(false) })

                    addView(importLyricsChoiceRow(
                        title = activity.getString(R.string.ui_word_by_word_lyrics_lrc_file),
                        subtitle = activity.getString(
                            if (wordByWordImportEnabled) {
                                R.string.ui_please_choose_a_word_by_word_lrc_file
                            } else {
                                R.string.ui_word_by_word_blocked_by_plain_lrc
                            }
                        ),
                        primary = false,
                        rowEnabled = wordByWordImportEnabled
                    ) { launchImport(true) })

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
                    body = {
                        addView(content)
                    }
                )
            }
        }
    }

    private fun importLyricsChoiceRow(
        title: String,
        subtitle: String,
        primary: Boolean,
        rowEnabled: Boolean = true,
        onClick: () -> Unit
    ): TextView {
        return TextView(activity).apply {
            text = activity.getString(R.string.ui_title_subtitle, title, subtitle)
            textSize = AirUiTokens.TextSize.Button
            typeface = Typeface.DEFAULT_BOLD
            val usePrimary = primary && rowEnabled
            isEnabled = rowEnabled
            alpha = if (rowEnabled) 1f else 0.68f
            setTextColor(when {
                !rowEnabled -> uiHost.colorTextMuted
                usePrimary -> uiHost.colorOnAccent
                else -> uiHost.colorTextStrong
            })
            setLineSpacing(uiHost.dp(AirUiTokens.Space.Xxs).toFloat(), 1f)
            setPadding(
                uiHost.dp(AirUiTokens.Space.ButtonH),
                uiHost.dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs),
                uiHost.dp(AirUiTokens.Space.ButtonH),
                uiHost.dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs)
            )
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, uiHost.dp(AirUiTokens.Space.Xl), 0, 0)
            layoutParams = params
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
