package com.andsi.airlyrics.app.controller

import com.andsi.airlyrics.R

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import com.andsi.airlyrics.app.contracts.FloatingLyricsReloader
import com.andsi.airlyrics.app.contracts.MainDialogHost
import com.andsi.airlyrics.app.contracts.MainTaskRunner
import com.andsi.airlyrics.app.contracts.MediaControllerProvider
import com.andsi.airlyrics.app.render.UiInvalidator
import com.andsi.airlyrics.media.displayText
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.lyrics.importer.enhancedLyricsFormatErrorMessage
import com.andsi.airlyrics.lyrics.importer.plainLyricsFormatErrorMessage
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.media.CurrentMediaReader
import com.andsi.airlyrics.media.MediaSourceStore

internal class LyricsController(
    private val context: Context,
    private val invalidator: UiInvalidator,
    private val taskRunner: MainTaskRunner,
    private val dialogHost: MainDialogHost,
    private val mediaControllerProvider: MediaControllerProvider,
    private val floatingLyricsReloader: FloatingLyricsReloader
) {
    fun importLyricsForCurrentMedia(
        uri: Uri,
        media: CurrentMediaInfo,
        overwrite: Boolean,
        importAsWordByWord: Boolean = false
    ) {
        taskRunner.runOnAppIo {
            val blockedImportResult = blockedImportResult(media, importAsWordByWord)
            if (blockedImportResult != null) {
                taskRunner.runOnMainThread {
                    handleImportResult(blockedImportResult, importAsWordByWord)
                }
                return@runOnAppIo
            }

            if (!overwrite && hasLyricsForMedia(media, importAsWordByWord)) {
                taskRunner.runOnMainThread {
                    val overwriteMessage = media.displayText + "\n\n" + if (importAsWordByWord) {
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
                        importLyricsForCurrentMedia(
                            uri = uri,
                            media = media,
                            overwrite = true,
                            importAsWordByWord = importAsWordByWord
                        )
                    }
                }
                return@runOnAppIo
            }

            taskRunner.runOnMainThread {
                Toast.makeText(context, context.getString(R.string.ui_importing_lyrics), Toast.LENGTH_SHORT).show()
            }

            val result = if (importAsWordByWord) {
                LyricsStorage.importKaraokeLyricsFromUriWithResult(
                    context = context,
                    uri = uri,
                    title = media.title,
                    artist = media.artist,
                    duration = media.durationMs,
                    album = media.album,
                    overwrite = overwrite
                )
            } else {
                LyricsStorage.importLyricsFromUriWithResult(
                    context = context,
                    uri = uri,
                    title = media.title,
                    artist = media.artist,
                    duration = media.durationMs,
                    album = media.album,
                    overwrite = overwrite
                )
            }

            taskRunner.runOnMainThread {
                handleImportResult(result, importAsWordByWord)
            }
        }
    }

    private fun blockedImportResult(
        media: CurrentMediaInfo,
        importAsWordByWord: Boolean
    ): LyricsStorage.ImportLyricsResult? {
        return if (importAsWordByWord) {
            val localInfo = LyricsStorage.getLocalLyricsInfo(
                context = context,
                title = media.title,
                artist = media.artist,
                duration = media.durationMs
            )
            if (localInfo != null && localInfo.source != LyricsStorage.SOURCE_KARAOKE_FALLBACK) {
                LyricsStorage.ImportLyricsResult.PlainLyricsAlreadyExists
            } else {
                null
            }
        } else if (LyricsStorage.hasKaraokeLyrics(
                context = context,
                title = media.title,
                artist = media.artist,
                duration = media.durationMs
            )
        ) {
            LyricsStorage.ImportLyricsResult.WordByWordLyricsAlreadyExists
        } else {
            null
        }
    }

    private fun hasLyricsForMedia(media: CurrentMediaInfo, importAsWordByWord: Boolean): Boolean {
        return if (importAsWordByWord) {
            LyricsStorage.hasKaraokeLyrics(
                context = context,
                title = media.title,
                artist = media.artist,
                duration = media.durationMs
            )
        } else {
            LyricsStorage.hasLocalLyrics(
                context = context,
                title = media.title,
                artist = media.artist,
                duration = media.durationMs
            )
        }
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
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                floatingLyricsReloader.reloadFloatingLyrics()
                invalidator.refreshCurrentPage(animateContent = false, animateTabs = false)
            }
            LyricsStorage.ImportLyricsResult.TooLarge -> {
                Toast.makeText(context, context.getString(R.string.ui_lrc_file_too_large), Toast.LENGTH_LONG).show()
            }
            is LyricsStorage.ImportLyricsResult.InvalidFormat -> {
                showImportFormatError(result.invalidLineNumbers, importAsWordByWord)
            }
            LyricsStorage.ImportLyricsResult.PlainLyricsAlreadyExists -> {
                Toast.makeText(context, context.getString(R.string.ui_enhanced_lrc_blocked_by_plain_lrc), Toast.LENGTH_LONG).show()
            }
            LyricsStorage.ImportLyricsResult.WordByWordLyricsAlreadyExists -> {
                Toast.makeText(context, context.getString(R.string.ui_plain_lrc_blocked_by_enhanced_lrc), Toast.LENGTH_LONG).show()
            }
            LyricsStorage.ImportLyricsResult.ReadFailed -> {
                val message = if (importAsWordByWord) {
                    context.getString(R.string.ui_cannot_read_enhanced_lrc_file)
                } else {
                    context.getString(R.string.ui_cannot_read_this_lyric_file)
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
            LyricsStorage.ImportLyricsResult.SaveFailed -> {
                Toast.makeText(context, context.getString(R.string.ui_lrc_import_save_failed), Toast.LENGTH_LONG).show()
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
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    floatingLyricsReloader.reloadFloatingLyrics()
                    invalidator.refreshCurrentPage(animateContent = false, animateTabs = false)
                } else {
                    val message = when (mode) {
                        LyricsStorage.DeleteMode.PLAIN -> context.getString(R.string.ui_no_plain_lrc_to_remove_for_this_song)
                        LyricsStorage.DeleteMode.KARAOKE -> context.getString(R.string.ui_no_enhanced_lrc_to_remove)
                        LyricsStorage.DeleteMode.ALL -> context.getString(R.string.ui_no_local_lyrics_to_remove)
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
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

        Toast.makeText(context, context.getString(R.string.ui_lyrics_save_folder_copied), Toast.LENGTH_LONG).show()
    }
}
