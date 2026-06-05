package com.andsi.airlyrics.app.controller

import com.andsi.airlyrics.R

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.net.Uri
import android.widget.Toast
import com.andsi.airlyrics.app.render.UiInvalidator
import com.andsi.airlyrics.floating.model.CurrentMediaInfo
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
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
            LyricsStorage.ImportLyricsResult.SAVED -> {
                val message = if (importAsWordByWord) {
                    context.getString(R.string.ui_enhanced_lrc_import_success)
                } else {
                    context.getString(R.string.ui_plain_lrc_import_success)
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                floatingLyricsReloader.reloadFloatingLyrics()
                invalidator.refresh(animateContent = false, animateTabs = false)
            }
            LyricsStorage.ImportLyricsResult.TOO_LARGE -> {
                Toast.makeText(context, context.getString(R.string.ui_lrc_file_too_large), Toast.LENGTH_LONG).show()
            }
            LyricsStorage.ImportLyricsResult.INVALID_FORMAT -> {
                if (importAsWordByWord) {
                    dialogHost.showInfoDialog(
                        title = context.getString(R.string.ui_enhanced_lrc_import_failed),
                        message = context.getString(R.string.ui_enhanced_lrc_no_word_time_error)
                    )
                } else {
                    Toast.makeText(context, context.getString(R.string.ui_lrc_import_format_error), Toast.LENGTH_LONG).show()
                }
            }
            LyricsStorage.ImportLyricsResult.SAVE_FAILED -> {
                val message = if (importAsWordByWord) {
                    context.getString(R.string.ui_enhanced_lrc_import_failed)
                } else {
                    context.getString(R.string.ui_lrc_import_format_error)
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
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
                    invalidator.refresh(animateContent = false, animateTabs = false)
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

    fun getCurrentMediaSnapshot(): CurrentMediaInfo? {
        val selectedPackage = MediaSourceStore.getSelectedPackage(context)
        val controllers = mediaControllerProvider.getActiveControllers().filter { it.metadata != null || it.playbackState != null }
        val controller = controllers.firstOrNull { it.packageName == selectedPackage }
            ?: controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull()
            ?: return null

        val metadata = controller.metadata ?: return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().trim()
        if (title.isBlank()) return null

        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val state = controller.playbackState

        return CurrentMediaInfo(
            sourcePackage = controller.packageName,
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            isPlaying = state?.state == PlaybackState.STATE_PLAYING,
            positionMs = state?.position ?: 0L
        )
    }

    fun showLyricsDir() {
        val path = LyricsStorage.getLyricsDirRawPath(context)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.ui_lyrics_save_folder), path))

        Toast.makeText(context, context.getString(R.string.ui_lyrics_save_folder_copied), Toast.LENGTH_LONG).show()
    }
}
