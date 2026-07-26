package com.andsi.airlyrics.app.controller

import com.andsi.airlyrics.R

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.util.Log
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.app.contracts.FloatingLyricsReloader
import com.andsi.airlyrics.app.contracts.MainDialogHost
import com.andsi.airlyrics.app.contracts.MainTaskRunner
import com.andsi.airlyrics.app.contracts.MediaControllerProvider
import com.andsi.airlyrics.app.render.UiInvalidator
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.lyrics.importer.enhancedLyricsFormatErrorMessage
import com.andsi.airlyrics.lyrics.importer.plainLyricsFormatErrorMessage
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.media.CurrentMediaReader
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.settings.AirToast
import com.andsi.airlyrics.ui.refresh.PageRebuildReason

internal class LyricsController(
    private val context: Context,
    private val invalidator: UiInvalidator,
    private val taskRunner: MainTaskRunner,
    private val dialogHost: MainDialogHost,
    private val mediaControllerProvider: MediaControllerProvider,
    private val floatingLyricsReloader: FloatingLyricsReloader
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
                    val overwriteMessage = target.displayText + "\n\n" + if (importAsWordByWord) {
                        context.getString(R.string.ui_overwrite_enhanced_keep_plain_msg)
                    } else {
                        context.getString(R.string.ui_overwrite_plain_keep_enhanced_msg)
                    }
                    dialogHost.showConfirmDialog(
                        title = if (importAsWordByWord) {
                            context.getString(R.string.ui_overwrite_local_enhanced_lrc)
                        } else {
                            context.getString(R.string.ui_overwrite_plain_lyrics)
                        },
                        message = overwriteMessage,
                        positiveText = context.getString(R.string.ui_overwrite)
                    ) {
                        importLyricsForTarget(
                            uri = uri,
                            target = target,
                            overwrite = true,
                            importAsWordByWord = importAsWordByWord
                        )
                    }
                }
                return@runOnAppIo
            }

            taskRunner.runOnMainThread {
                AirToast.showShort(context, R.string.ui_importing_lyrics)
            }

            val result = if (importAsWordByWord) {
                LyricsStorage.importKaraokeLyricsFromUriWithResult(
                    context = context,
                    uri = uri,
                    title = target.title,
                    artist = target.artist,
                    duration = target.durationMs,
                    album = target.album,
                    overwrite = overwrite
                )
            } else {
                LyricsStorage.importLyricsFromUriWithResult(
                    context = context,
                    uri = uri,
                    title = target.title,
                    artist = target.artist,
                    duration = target.durationMs,
                    album = target.album,
                    overwrite = overwrite
                )
            }

            taskRunner.runOnMainThread {
                handleImportResult(result, importAsWordByWord)
            }
        }
    }

    private fun blockedImportResult(
        target: SongIdentity,
        importAsWordByWord: Boolean
    ): LyricsStorage.ImportLyricsResult? {
        return if (importAsWordByWord) {
            val localInfo = LyricsStorage.getLocalLyricsInfo(
                context = context,
                title = target.title,
                artist = target.artist,
                duration = target.durationMs
            )
            if (localInfo != null && localInfo.source != LyricsStorage.SOURCE_KARAOKE_FALLBACK) {
                LyricsStorage.ImportLyricsResult.PlainLyricsAlreadyExists
            } else {
                null
            }
        } else if (LyricsStorage.hasKaraokeLyrics(
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
            LyricsStorage.hasKaraokeLyrics(
                context = context,
                title = target.title,
                artist = target.artist,
                duration = target.durationMs
            )
        } else {
            LyricsStorage.hasLocalLyrics(
                context = context,
                title = target.title,
                artist = target.artist,
                duration = target.durationMs
            )
        }
    }

    private val SongIdentity.displayText: String
        get() = if (artist.isNotBlank()) {
            "♪ $title - $artist"
        } else {
            "♪ $title"
        }

    private fun handleImportResult(
        result: LyricsStorage.ImportLyricsResult,
        importAsWordByWord: Boolean
    ) {
        when (result) {
            LyricsStorage.ImportLyricsResult.Saved -> {
                val message = if (importAsWordByWord) {
                    context.getString(R.string.ui_enhanced_lrc_import_success)
                } else {
                    context.getString(R.string.ui_plain_lrc_import_success)
                }
                AirToast.showLong(context, message)
                floatingLyricsReloader.reloadFloatingLyrics()
                invalidator.rebuildCurrentPage(
                    reason = PageRebuildReason.LYRICS_CHANGED,
                    animateContent = false,
                    animateTabs = false
                )
            }
            LyricsStorage.ImportLyricsResult.TooLarge -> {
                AirToast.showLong(context, R.string.ui_lrc_file_too_large)
            }
            is LyricsStorage.ImportLyricsResult.InvalidFormat -> {
                showImportFormatError(result.invalidLineNumbers, importAsWordByWord)
            }
            LyricsStorage.ImportLyricsResult.PlainLyricsAlreadyExists -> {
                AirToast.showLong(context, R.string.ui_enhanced_lrc_blocked_by_plain_lrc)
            }
            LyricsStorage.ImportLyricsResult.WordByWordLyricsAlreadyExists -> {
                AirToast.showLong(context, R.string.ui_plain_lrc_blocked_by_enhanced_lrc)
            }
            LyricsStorage.ImportLyricsResult.ReadFailed -> {
                val message = if (importAsWordByWord) {
                    context.getString(R.string.ui_cannot_read_enhanced_lrc_file)
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
                    "Karaoke import rollback failed after ${result.originalFailureStep} " +
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
            context.enhancedLyricsFormatErrorMessage(invalidLineNumbers)
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
                        LyricsStorage.DeleteMode.KARAOKE -> context.getString(R.string.ui_enhanced_lrc_removed_for_this_song)
                        LyricsStorage.DeleteMode.ALL -> context.getString(R.string.ui_all_local_lyrics_removed)
                    }
                    AirToast.showLong(context, message)
                    floatingLyricsReloader.reloadFloatingLyrics()
                    invalidator.rebuildCurrentPage(
                        reason = PageRebuildReason.LYRICS_CHANGED,
                        animateContent = false,
                        animateTabs = false
                    )
                } else {
                    val message = when (mode) {
                        LyricsStorage.DeleteMode.PLAIN -> context.getString(R.string.ui_no_plain_lrc_to_remove_for_this_song)
                        LyricsStorage.DeleteMode.KARAOKE -> context.getString(R.string.ui_no_enhanced_lrc_to_remove)
                        LyricsStorage.DeleteMode.ALL -> context.getString(R.string.ui_no_local_lyrics_to_remove)
                    }
                    AirToast.showShort(context, message)
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
