package com.andsi.airlyrics.app.controller

import com.andsi.airlyrics.R

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.util.Log
import com.andsi.airlyrics.core.model.SongIdentity
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
import com.andsi.airlyrics.settings.AirToast

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
    private val lyricsImportGateway: LyricsImportGateway = StorageLyricsImportGateway(),
    private val onlineLyricsLookupGateway: OnlineLyricsLookupGateway = RepositoryOnlineLyricsLookupGateway
) {
    fun importLyricsForTarget(
        uri: Uri,
        target: SongIdentity,
        overwrite: Boolean,
        importAsWordByWord: Boolean = false
    ) {
        taskRunner.runOnAppIo {
            val blockedImportResult = blockedImportResult(target, importAsWordByWord)
            if (blockedImportResult != null) {
                taskRunner.runOnMainThread {
                    handleImportResult(blockedImportResult, importAsWordByWord)
                }
                return@runOnAppIo
            }

            if (!overwrite && hasLyricsForTarget(target, importAsWordByWord)) {
                taskRunner.runOnMainThread {
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

            taskRunner.runOnMainThread {
                AirToast.showShort(context, R.string.ui_importing_lyrics)
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

            taskRunner.runOnMainThread {
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
        val overwriteMessage = request.target.displayText + "\n\n" + if (importAsWordByWord) {
            context.getString(R.string.ui_word_by_word_overwrite_message)
        } else {
            context.getString(R.string.ui_overwrite_plain_lyrics_msg)
        }
        dialogHost.showConfirmDialog(
            title = if (importAsWordByWord) {
                context.getString(R.string.ui_overwrite_local_word_by_word_lyrics)
            } else {
                context.getString(R.string.ui_overwrite_plain_lyrics)
            },
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
                val message = if (importAsWordByWord) {
                    context.getString(R.string.ui_word_by_word_lyrics_import_success)
                } else {
                    context.getString(R.string.ui_plain_lrc_import_success)
                }
                AirToast.showLong(context, message)
            }
            LyricsStorage.ImportLyricsResult.TooLarge -> {
                AirToast.showLong(context, R.string.ui_lrc_file_too_large)
            }
            is LyricsStorage.ImportLyricsResult.InvalidFormat -> {
                showImportFormatError(result.invalidLineNumbers, importAsWordByWord)
            }
            LyricsStorage.ImportLyricsResult.PlainLyricsAlreadyExists -> {
                AirToast.showLong(context, R.string.ui_word_by_word_blocked_by_plain_lrc)
            }
            LyricsStorage.ImportLyricsResult.WordByWordLyricsAlreadyExists -> {
                AirToast.showLong(context, R.string.ui_plain_lrc_blocked_by_word_by_word)
            }
            LyricsStorage.ImportLyricsResult.ReadFailed -> {
                val message = if (importAsWordByWord) {
                    context.getString(R.string.ui_cannot_read_word_by_word_lyrics_file)
                } else {
                    context.getString(R.string.ui_cannot_read_this_lyric_file)
                }
                AirToast.showLong(context, message)
            }
            LyricsStorage.ImportLyricsResult.SaveFailed -> {
                AirToast.showLong(context, R.string.ui_lrc_import_save_failed)
            }
            LyricsStorage.ImportLyricsResult.SnapshotFailed -> {
                AirToast.showLong(context, R.string.ui_lrc_import_save_failed)
            }
            is LyricsStorage.ImportLyricsResult.RollbackFailed -> {
                Log.e(
                    "LyricsController",
                    "Word-by-word lyrics import rollback failed after ${result.originalFailureStep} " +
                        "(${result.originalFailureCause}); " +
                        "failed steps=${result.failedRollbackSteps}"
                )
                AirToast.showLong(context, R.string.ui_lrc_import_save_failed)
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
        taskRunner.runOnAppIo {
            val deleted = LyricsStorage.deleteLocalLyrics(
                context = context,
                title = media.title,
                artist = media.artist,
                duration = media.durationMs,
                mode = mode
            )

            taskRunner.runOnMainThread {
                if (deleted) {
                    val message = when (mode) {
                        LyricsStorage.DeleteMode.PLAIN -> context.getString(R.string.ui_plain_lrc_removed_for_this_song)
                        LyricsStorage.DeleteMode.WORD_BY_WORD -> context.getString(R.string.ui_word_by_word_lyrics_removed)
                        LyricsStorage.DeleteMode.ALL -> context.getString(R.string.ui_all_local_lyrics_removed)
                    }
                    AirToast.showLong(context, message)
                    lyricsChangedPublisher.publishDeleted(media.toSongIdentity())
                } else {
                    val message = when (mode) {
                        LyricsStorage.DeleteMode.PLAIN -> context.getString(R.string.ui_no_plain_lrc_to_remove_for_this_song)
                        LyricsStorage.DeleteMode.WORD_BY_WORD -> context.getString(R.string.ui_no_word_by_word_lyrics_to_remove)
                        LyricsStorage.DeleteMode.ALL -> context.getString(R.string.ui_no_local_lyrics_to_remove)
                    }
                    AirToast.showShort(context, message)
                }
            }
        }
    }

    fun deleteSavedLyricsItem(
        item: LyricsStorage.LocalLyricsItem,
        onCompleted: (Boolean) -> Unit
    ) {
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

            taskRunner.runOnMainThread {
                when (result) {
                    is LyricsStorage.DeleteLocalLyricsItemResult.Deleted -> {
                        AirToast.showLong(context, R.string.ui_all_saved_lyrics_deleted)
                        val deletedTarget = result.target
                        val deletesCurrentWordByWordOnlyItem = currentLyricsInfo == null &&
                            deletedTarget != null &&
                            currentTarget?.isStrongSameSong(deletedTarget) == true
                        if (currentTarget != null && (deletesCurrentItem || deletesCurrentWordByWordOnlyItem)) {
                            lyricsChangedPublisher.publishDeleted(currentTarget)
                        }
                        onCompleted(true)
                    }

                    LyricsStorage.DeleteLocalLyricsItemResult.NotFound -> {
                        AirToast.showShort(context, R.string.ui_lyrics_not_found)
                        onCompleted(false)
                    }

                    LyricsStorage.DeleteLocalLyricsItemResult.Failed -> {
                        AirToast.showLong(context, R.string.ui_delete_saved_lyrics_failed)
                        onCompleted(false)
                    }
                }
            }
        }
    }

    fun deleteAllSavedLyrics() {
        taskRunner.runOnAppIo {
            val result = LyricsStorage.deleteAllSavedLyrics(context)

            taskRunner.runOnMainThread {
                when (result) {
                    LyricsStorage.DeleteAllSavedLyricsResult.DELETED -> {
                        AirToast.showLong(context, R.string.ui_all_saved_lyrics_deleted)
                        lyricsChangedPublisher.publishDeleted()
                    }

                    LyricsStorage.DeleteAllSavedLyricsResult.NOTHING_TO_DELETE -> {
                        AirToast.showShort(context, R.string.ui_no_saved_lyrics_to_delete)
                    }

                    LyricsStorage.DeleteAllSavedLyricsResult.FAILED -> {
                        AirToast.showLong(context, R.string.ui_delete_all_saved_lyrics_failed)
                        lyricsChangedPublisher.publishDeleted()
                    }
                }
            }
        }
    }

    fun searchOnlineLyricsForCurrentMedia(media: CurrentMediaInfo) {
        AirToast.showShort(context, R.string.ui_searching_online_again)
        taskRunner.runOnAppIo {
            val result = onlineLyricsLookupGateway.findAndSave(context, media)
            val foundLyrics = result.getOrNull()?.takeIf { it.plainLrc.isNotBlank() }
            val lookupError = result.exceptionOrNull() as? LyricsLookupException
            if (foundLyrics != null) {
                lyricsChangedPublisher.publish(media.toSongIdentity())
            }

            taskRunner.runOnMainThread {
                when {
                    foundLyrics != null -> AirToast.showLong(context, R.string.ui_online_lyrics_saved)
                    lookupError != null -> AirToast.showLong(
                        context,
                        context.localizedLyricsLookupMessage(lookupError)
                    )
                    result.isFailure -> AirToast.showLong(context, R.string.ui_online_lyrics_search_failed)
                    else -> AirToast.showLong(context, R.string.ui_lyrics_not_found)
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

        AirToast.showLong(context, R.string.ui_lyrics_save_folder_copied)
    }
}
