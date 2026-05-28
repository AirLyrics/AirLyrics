package com.andsi.airlyrics.app.controller

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
import com.andsi.airlyrics.media.MediaSourceStore

internal class LyricsController(
    private val activity: MainActivity
) {
    fun importLyricsForCurrentMedia(uri: Uri, media: CurrentMediaInfo, overwrite: Boolean) {
        val imported = LyricsStorage.importLyricsFromUri(
            context = activity,
            uri = uri,
            title = media.title,
            artist = media.artist,
            duration = media.durationMs,
            album = media.album,
            overwrite = overwrite
        )

        if (imported) {
            Toast.makeText(activity, "已为当前音乐导入歌词", Toast.LENGTH_LONG).show()
            activity.reloadFloatingLyrics()
            activity.renderCurrentPage(animateContent = false, animateTabs = false)
        } else {
            Toast.makeText(activity, "导入失败，可能不是可读取的歌词文件", Toast.LENGTH_LONG).show()
        }
    }

    fun deleteLyricsForCurrentMedia(media: CurrentMediaInfo) {
        val deleted = LyricsStorage.deleteLocalLyrics(
            context = activity,
            title = media.title,
            artist = media.artist,
            duration = media.durationMs
        )

        if (deleted) {
            Toast.makeText(activity, "已移除当前音乐的本地歌词", Toast.LENGTH_LONG).show()
            activity.reloadFloatingLyrics()
            activity.renderCurrentPage(animateContent = false, animateTabs = false)
        } else {
            Toast.makeText(activity, "当前音乐没有可移除的本地歌词", Toast.LENGTH_SHORT).show()
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
        clipboard.setPrimaryClip(ClipData.newPlainText("歌词保存目录", path))

        Toast.makeText(activity, "歌词保存目录已复制", Toast.LENGTH_LONG).show()
    }

}
