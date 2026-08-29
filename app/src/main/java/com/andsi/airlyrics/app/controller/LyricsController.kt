package com.andsi.airlyrics.app.controller

import com.andsi.airlyrics.R

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.util.Log
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.feedback.AirFeedback
import com.andsi.airlyrics.app.contracts.MainDialogHost
import com.andsi.airlyrics.app.contracts.MainTaskRunner
import com.andsi.airlyrics.app.contracts.MediaControllerProvider
import com.andsi.airlyrics.app.state.LyricsImportType
import com.andsi.airlyrics.app.state.PendingLyricsOverwrite
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.lyrics.importer.wordByWordLyricsFormatErrorMessage
import com.andsi.airlyrics.lyrics.importer.plainLyricsFormatErrorMessage
import com.andsi.airlyrics.lyrics.LyricsChangedPublisher
import com.andsi.airlyrics.lyrics.LyricsLookupException
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.i18n.localizedLyricsLookupMessage
import com.andsi.airlyrics.media.CurrentMediaReader
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.media.toSongIdentity

internal fun interface LyricsOverwriteConfirmationRequester {
    fun requestConfirmation(request: PendingLyricsOverwrite)
}

internal class LyricsController(
    private val context: Context,
    private val taskRunner: MainTaskRunner,
    private val dialogHost: MainDialogHost,
    private val mediaControllerProvider: MediaControllerProvider,
    private val overwriteConfirmationRequester: LyricsOverwriteConfirmationRequester,
    private val lyricsChangedPublisher: LyricsChangedPublisher,
    private val feedback: AirFeedback,
    private val lyricsImportGateway: LyricsImportGateway = StorageLyricsImportGateway(),
    private val onlineLyricsLookupGateway: OnlineLyricsLookupGateway = RepositoryOnlineLyricsLookupGateway
) {
    fun importLyricsForTarget(
        uri: Uri,
        target: SongIdentity,
        overwrite: Boolean,
        importAsWordByWord: Boolean = false
    ) {
        val uiGeneration = taskRunner.currentUiGeneration()
        taskRunner.runOnAppIo {
            val blockedImportResult = blockedImportResult(target, importAsWordByWord)
            if (blockedImportResult != null) {
                taskRunner.runOnStartedUi(uiGeneration) {
                    handleImportResult(blockedImportResult, importAsWordByWord)
                }
                return@runOnAppIo
            }

            if (!overwrite && hasLyricsForTarget(target, importAsWordByWord)) {
                taskRunner.runOnStartedUi(uiGeneration) {
                    overwriteConfirmationRequester.requestConfirmation(
                        PendingLyricsOverwrite(
                            uri = uri,
                            target = target,
                            type = if (importAsWordByWord) {
                                LyricsImportType.WORD_BY_WORD
                            } else {
                                LyricsImportType.PLAIN
                            }
                        )
                    )
                }
                return@runOnAppIo
            }

            taskRunner.runOnStartedUi(uiGeneration) {
                feedback.showMessage(R.string.ui_importing_lyrics)
            }

            val result = if (importAsWordByWord) {
                lyricsImportGateway.importWordByWordLyrics(
                    context = context,
                    uri = uri,
                    target = target,
                    overwrite = overwrite
                )
            } else {
                lyricsImportGateway.importPlainLyrics(
                    context = context,
                    uri = uri,
                    target = target,
                    overwrite = overwrite
                )
            }

            if (result == LyricsStorage.ImportLyricsResult.Saved) {
                lyricsChangedPublisher.publish(target)
            }

            taskRunner.runOnStartedUi(uiGeneration) {
                handleImportResult(result, importAsWordByWord)
            }
        }
    }

    fun showOverwriteConfirmation(
        request: PendingLyricsOverwrite,
        onPositive: () -> Unit,
        onNegative: () -> Unit
    ) {
        val importAsWordByWord = request.type == LyricsImportType.WORD_BY_WORD
        val overwriteMessage = request.target.displayText + "\n\n" + context.getString(
            if (importAsWordByWord) {
                R.string.ui_word_by_word_overwrite_message
            } else {
                R.string.ui_overwrite_plain_lyrics_msg
            }
        )
        dialogHost.showConfirmDialog(
            title = context.getString(
                if (importAsWordByWord) {
                    R.string.ui_overwrite_local_word_by_word_lyrics
                } else {
                    R.string.ui_overwrite_plain_lyrics
                }
            ),
            message = overwriteMessage,
            positiveText = context.getString(R.string.ui_overwrite),
            onPositive = onPositive,
            onNegative = onNegative
        )
    }

    private fun blockedImportResult(
        target: SongIdentity,
        importAsWordByWord: Boolean
    ): LyricsStorage.ImportLyricsResult? {
        return if (importAsWordByWord) {
            val localInfo = LyricsStorage.getLocalPlainLyricsInfo(
                context = context,
                title = target.title,
                artist = target.artist,
                duration = target.durationMs
            )
            if (localInfo != null && localInfo.plainSource != LyricsStorage.SOURCE_WORD_BY_WORD_FALLBACK) {
                LyricsStorage.ImportLyricsResult.PlainLyricsAlreadyExists
            } else {
                null
            }
        } else if (LyricsStorage.hasWordByWordLyrics(
                context = context,
                title = target.title,
                artist = target.artist,
                duration = target.durationMs
            )
        ) {
            LyricsStorage.ImportLyricsResult.WordByWordLyricsAlreadyExists
        } else {
            null
        }
    }

    private fun hasLyricsForTarget(target: SongIdentity, importAsWordByWord: Boolean): Boolean {
        return if (importAsWordByWord) {
            LyricsStorage.hasWordByWordLyrics(
                context = context,
                title = target.title,
                artist = target.artist,
                duration = target.durationMs
            )
        } else {
            LyricsStorage.hasPlainLyrics(
                context = context,
                title = target.title,
                artist = target.artist,
                duration = target.durationMs
            )
        }
    }

    private val SongIdentity.displayText: String
        get() = if (artist.isNotBlank()) {
            "$title - $artist"
        } else {
            title
        }

    private fun handleImportResult(
        result: LyricsStorage.ImportLyricsResult,
        importAsWordByWord: Boolean
    ) {
        when (result) {
            LyricsStorage.ImportLyricsResult.Saved -> {
                val messageRes = if (importAsWordByWord) {
                    R.string.ui_word_by_word_lyrics_import_success
                } else {
                    R.string.ui_plain_lrc_import_success
                }
                feedback.showMessage(messageRes)
            }
            LyricsStorage.ImportLyricsResult.TooLarge -> {
                feedback.showError(R.string.ui_lrc_file_too_large)
            }
            is LyricsStorage.ImportLyricsResult.InvalidFormat -> {
                showImportFormatError(result.invalidLineNumbers, importAsWordByWord)
            }
            LyricsStorage.ImportLyricsResult.PlainLyricsAlreadyExists -> {
                feedback.showError(R.string.ui_word_by_word_blocked_by_plain_lrc)
            }
            LyricsStorage.ImportLyricsResult.WordByWordLyricsAlreadyExists -> {
                feedback.showError(R.string.ui_plain_lrc_blocked_by_word_by_word)
            }
            LyricsStorage.ImportLyricsResult.ReadFailed -> {
                val messageRes = if (importAsWordByWord) {
                    R.string.ui_cannot_read_word_by_word_lyrics_file
                } else {
                    R.string.ui_cannot_read_this_lyric_file
                }
                feedback.showError(messageRes)
            }
            LyricsStorage.ImportLyricsResult.SaveFailed -> {
                feedback.showError(R.string.ui_lrc_import_save_failed)
            }
            LyricsStorage.ImportLyricsResult.SnapshotFailed -> {
                feedback.showError(R.string.ui_lrc_import_save_failed)
            }
            is LyricsStorage.ImportLyricsResult.RollbackFailed -> {
                Log.e(
                    "LyricsController",
                    "Word-by-word lyrics import rollback failed after ${result.originalFailureStep} " +
                        "(${result.originalFailureCause}); " +
                        "failed steps=${result.failedRollbackSteps}"
                )
                feedback.showError(R.string.ui_lrc_import_save_failed)
            }
        }
    }

    private fun showImportFormatError(
        invalidLineNumbers: List<Int>,
        importAsWordByWord: Boolean
    ) {
        val message = if (importAsWordByWord) {
            context.wordByWordLyricsFormatErrorMessage(invalidLineNumbers)
        } else {
            context.plainLyricsFormatErrorMessage(invalidLineNumbers)
        }
        dialogHost.showInfoDialog(
            title = context.getString(R.string.ui_invalid_format),
            message = message
        )
    }

    fun deleteLyricsForCurrentMedia(media: CurrentMediaInfo, mode: LyricsStorage.DeleteMode) {
        val uiGeneration = taskRunner.currentUiGeneration()
        taskRunner.runOnAppIo {
            val deleted = LyricsStorage.deleteLocalLyrics(
                context = context,
                title = media.title,
                artist = media.artist,
                duration = media.durationMs,
                mode = mode
            )
            if (deleted) {
                lyricsChangedPublisher.publishDeleted(media.toSongIdentity())
            }

            taskRunner.runOnStartedUi(uiGeneration) {
                if (deleted) {
                    val messageRes = when (mode) {
                        LyricsStorage.DeleteMode.PLAIN -> R.string.ui_plain_lrc_removed_for_this_song
                        LyricsStorage.DeleteMode.WORD_BY_WORD -> R.string.ui_word_by_word_lyrics_removed
                        LyricsStorage.DeleteMode.ALL -> R.string.ui_all_local_lyrics_removed
                    }
                    feedback.showMessage(messageRes)
                } else {
                    val messageRes = when (mode) {
                        LyricsStorage.DeleteMode.PLAIN -> R.string.ui_no_plain_lrc_to_remove_for_this_song
                        LyricsStorage.DeleteMode.WORD_BY_WORD -> R.string.ui_no_word_by_word_lyrics_to_remove
                        LyricsStorage.DeleteMode.ALL -> R.string.ui_no_local_lyrics_to_remove
                    }
                    feedback.showMessage(messageRes)
                }
            }
        }
    }

    fun deleteSavedLyricsItem(
        item: LyricsStorage.LocalLyricsItem,
        onCompleted: (Boolean) -> Unit
    ) {
        val uiGeneration = taskRunner.currentUiGeneration()
        val currentMedia = getCurrentMediaInfo()
        val currentTarget = currentMedia?.toSongIdentity()
        taskRunner.runOnAppIo {
            val currentLyricsInfo = currentMedia?.let { media ->
                LyricsStorage.getLocalPlainLyricsInfo(
                    context = context,
                    title = media.title,
                    artist = media.artist,
                    duration = media.durationMs
                )
            }
            val deletesCurrentItem = currentLyricsInfo?.let { info ->
                if (item.indexKey.isNotBlank()) {
                    item.indexKey == info.indexKey
                } else {
                    info.indexKey.isBlank() && item.name == info.plainFileName
                }
            } == true
            val result = LyricsStorage.deleteLocalLyricsItem(context, item)
            if (result is LyricsStorage.DeleteLocalLyricsItemResult.Deleted) {
                val deletedTarget = result.target
                val deletesCurrentWordByWordOnlyItem = currentLyricsInfo == null &&
                    deletedTarget != null &&
                    currentTarget?.isStrongSameSong(deletedTarget) == true
                if (currentTarget != null &&
                    (deletesCurrentItem || deletesCurrentWordByWordOnlyItem)
                ) {
                    lyricsChangedPublisher.publishDeleted(currentTarget)
                }
            }

            taskRunner.runOnStartedUi(uiGeneration) {
                when (result) {
                    is LyricsStorage.DeleteLocalLyricsItemResult.Deleted -> {
                        feedback.showMessage(R.string.ui_all_saved_lyrics_deleted)
                        onCompleted(true)
                    }

                    LyricsStorage.DeleteLocalLyricsItemResult.NotFound -> {
                        feedback.showMessage(R.string.ui_lyrics_not_found)
                        onCompleted(false)
                    }

                    LyricsStorage.DeleteLocalLyricsItemResult.Failed -> {
                        feedback.showError(R.string.ui_delete_saved_lyrics_failed)
                        onCompleted(false)
                    }
                }
            }
        }
    }

    fun deleteAllSavedLyrics() {
        val uiGeneration = taskRunner.currentUiGeneration()
        taskRunner.runOnAppIo {
            val result = LyricsStorage.deleteAllSavedLyrics(context)
            if (result != LyricsStorage.DeleteAllSavedLyricsResult.NOTHING_TO_DELETE) {
                lyricsChangedPublisher.publishDeleted()
            }

            taskRunner.runOnStartedUi(uiGeneration) {
                when (result) {
                    LyricsStorage.DeleteAllSavedLyricsResult.DELETED -> {
                        feedback.showMessage(R.string.ui_all_saved_lyrics_deleted)
                    }

                    LyricsStorage.DeleteAllSavedLyricsResult.NOTHING_TO_DELETE -> {
                        feedback.showMessage(R.string.ui_no_saved_lyrics_to_delete)
                    }

                    LyricsStorage.DeleteAllSavedLyricsResult.FAILED -> {
                        feedback.showError(R.string.ui_delete_all_saved_lyrics_failed)
                    }
                }
            }
        }
    }

    fun searchOnlineLyricsForCurrentMedia(media: CurrentMediaInfo) {
        feedback.showMessage(R.string.ui_searching_online_again)
        val uiGeneration = taskRunner.currentUiGeneration()
        taskRunner.runOnAppIo {
            val result = onlineLyricsLookupGateway.findAndSave(context, media)
            val foundLyrics = result.getOrNull()?.takeIf { it.plainLrc.isNotBlank() }
            val lookupError = result.exceptionOrNull() as? LyricsLookupException
            if (foundLyrics != null) {
                lyricsChangedPublisher.publish(media.toSongIdentity())
            }

            taskRunner.runOnStartedUi(uiGeneration) {
                when {
                    foundLyrics != null -> feedback.showMessage(R.string.ui_online_lyrics_saved)
                    lookupError != null -> feedback.showError(
                        context.localizedLyricsLookupMessage(lookupError)
                    )
                    result.isFailure -> feedback.showError(R.string.ui_online_lyrics_search_failed)
                    else -> feedback.showMessage(R.string.ui_lyrics_not_found)
                }
            }
        }
    }

    fun getCurrentMediaInfo(): CurrentMediaInfo? {
        val selectedPackage = MediaSourceStore.getSelectedPackage(context)
        val selectedController = CurrentMediaReader.selectedController(
            controllers = mediaControllerProvider.getActiveControllers(),
            selectedPackage = selectedPackage
        )
        return selectedController?.let { CurrentMediaReader.currentMediaFromController(it) }
    }

    fun showLyricsDir() {
        val path = LyricsStorage.getLyricsDirRawPath(context)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.ui_lyrics_save_folder), path))

        feedback.showMessage(R.string.ui_lyrics_save_folder_copied)
    }
}
