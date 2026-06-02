package com.andsi.airlyrics.app.controller

import com.andsi.airlyrics.R

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.net.Uri
import android.widget.Toast
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.app.renderCurrentPage
import com.andsi.airlyrics.floating.model.CurrentMediaInfo
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.ui.components.showAirConfirmDialog
import com.andsi.airlyrics.ui.components.showAirInfoDialog
import com.andsi.airlyrics.media.MediaSourceStore

internal class LyricsController(
    private val activity: MainActivity
) {
    fun importLyricsForCurrentMedia(
        uri: Uri,
        media: CurrentMediaInfo,
        overwrite: Boolean,
        importAsWordByWord: Boolean = false
    ) {
        activity.runOnAppIo {
            if (!overwrite && hasLyricsForMedia(media, importAsWordByWord)) {
                activity.runOnMainThread {
                    val overwriteMessage = media.displayText + "\n\n" + if (importAsWordByWord) {
                        activity.getString(R.string.ui_overwrite_enhanced_keep_plain_msg)
                    } else {
                        activity.getString(R.string.ui_overwrite_plain_keep_enhanced_msg)
                    }
                    activity.showAirConfirmDialog(
                        title = if (importAsWordByWord) {
                            activity.getString(R.string.ui_overwrite_local_enhanced_lrc)
                        } else {
                            activity.getString(R.string.ui_overwrite_plain_lyrics)
                        },
                        message = overwriteMessage,
                        positiveText = activity.getString(R.string.ui_overwrite)
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

            activity.runOnMainThread {
                Toast.makeText(activity, activity.getString(R.string.ui_importing_lyrics), Toast.LENGTH_SHORT).show()
            }

            val result = if (importAsWordByWord) {
                LyricsStorage.importKaraokeLyricsFromUriWithResult(
                    context = activity,
                    uri = uri,
                    title = media.title,
                    artist = media.artist,
                    duration = media.durationMs,
                    album = media.album,
                    overwrite = overwrite
                )
            } else {
                LyricsStorage.importLyricsFromUriWithResult(
                    context = activity,
                    uri = uri,
                    title = media.title,
                    artist = media.artist,
                    duration = media.durationMs,
                    album = media.album,
                    overwrite = overwrite
                )
            }

            activity.runOnMainThread {
                handleImportResult(result, importAsWordByWord)
            }
        }
    }

    private fun hasLyricsForMedia(media: CurrentMediaInfo, importAsWordByWord: Boolean): Boolean {
        return if (importAsWordByWord) {
            LyricsStorage.hasKaraokeLyrics(
                context = activity,
                title = media.title,
                artist = media.artist,
                duration = media.durationMs
            )
        } else {
            LyricsStorage.hasLocalLyrics(
                context = activity,
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
                    activity.getString(R.string.ui_enhanced_lrc_import_success)
                } else {
                    activity.getString(R.string.ui_plain_lrc_import_success)
                }
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                activity.reloadFloatingLyrics()
                activity.renderCurrentPage(animateContent = false, animateTabs = false)
            }
            LyricsStorage.ImportLyricsResult.TOO_LARGE -> {
                Toast.makeText(activity, activity.getString(R.string.ui_lrc_file_too_large), Toast.LENGTH_LONG).show()
            }
            LyricsStorage.ImportLyricsResult.INVALID_FORMAT -> {
                if (importAsWordByWord) {
                    activity.showAirInfoDialog(
                        title = activity.getString(R.string.ui_enhanced_lrc_import_failed),
                        message = activity.getString(R.string.ui_enhanced_lrc_no_word_time_error)
                    )
                } else {
                    Toast.makeText(activity, activity.getString(R.string.ui_lrc_import_format_error), Toast.LENGTH_LONG).show()
                }
            }
            LyricsStorage.ImportLyricsResult.SAVE_FAILED -> {
                val message = if (importAsWordByWord) {
                    activity.getString(R.string.ui_enhanced_lrc_import_failed)
                } else {
                    activity.getString(R.string.ui_lrc_import_format_error)
                }
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun deleteLyricsForCurrentMedia(media: CurrentMediaInfo, mode: LyricsStorage.DeleteMode) {
        activity.runOnAppIo {
            val deleted = LyricsStorage.deleteLocalLyrics(
                context = activity,
                title = media.title,
                artist = media.artist,
                duration = media.durationMs,
                mode = mode
            )

            activity.runOnMainThread {
                if (deleted) {
                    val message = when (mode) {
                        LyricsStorage.DeleteMode.PLAIN -> activity.getString(R.string.ui_plain_lrc_removed_for_this_song)
                        LyricsStorage.DeleteMode.KARAOKE -> activity.getString(R.string.ui_enhanced_lrc_removed_for_this_song)
                        LyricsStorage.DeleteMode.ALL -> activity.getString(R.string.ui_all_local_lyrics_removed)
                    }
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                    activity.reloadFloatingLyrics()
                    activity.renderCurrentPage(animateContent = false, animateTabs = false)
                } else {
                    val message = when (mode) {
                        LyricsStorage.DeleteMode.PLAIN -> activity.getString(R.string.ui_no_plain_lrc_to_remove_for_this_song)
                        LyricsStorage.DeleteMode.KARAOKE -> activity.getString(R.string.ui_no_enhanced_lrc_to_remove)
                        LyricsStorage.DeleteMode.ALL -> activity.getString(R.string.ui_no_local_lyrics_to_remove)
                    }
                    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun getCurrentMediaSnapshot(): CurrentMediaInfo? {
        val selectedPackage = MediaSourceStore.getSelectedPackage(activity)
        val controllers = activity.getActiveMediaControllers().filter { it.metadata != null || it.playbackState != null }
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
        val path = LyricsStorage.getLyricsDirRawPath(activity)

        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.ui_lyrics_save_folder), path))

        Toast.makeText(activity, activity.getString(R.string.ui_lyrics_save_folder_copied), Toast.LENGTH_LONG).show()
    }

}
