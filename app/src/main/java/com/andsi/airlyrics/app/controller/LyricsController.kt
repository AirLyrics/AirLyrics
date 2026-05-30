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
        val imported = if (importAsWordByWord) {
            LyricsStorage.importKaraokeLyricsFromUri(
                context = activity,
                uri = uri,
                title = media.title,
                artist = media.artist,
                duration = media.durationMs,
                album = media.album,
                overwrite = overwrite
            )
        } else {
            LyricsStorage.importLyricsFromUri(
                context = activity,
                uri = uri,
                title = media.title,
                artist = media.artist,
                duration = media.durationMs,
                album = media.album,
                overwrite = overwrite
            )
        }

        if (imported) {
            val message = if (importAsWordByWord) {
                "已导入逐字歌词，悬浮窗显示中会立即刷新"
            } else {
                "已导入普通歌词，悬浮窗显示中会立即刷新"
            }
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            activity.reloadFloatingLyrics()
            activity.renderCurrentPage(animateContent = false, animateTabs = false)
        } else {
            if (importAsWordByWord) {
                activity.showAirInfoDialog(
                    title = "逐字歌词导入失败",
                    message = "没有识别到 enhanced LRC 逐字时间戳。\n\n" +
                        "请确认文件是 .lrc，并使用类似格式：\n" +
                        "[00:12.34]<00:12.34>这<00:12.50>是<00:12.70>逐字歌词\n\n" +
                        "普通 LRC 只有 [00:12.34]整句歌词，需要选择“普通歌词”导入。"
                )
            } else {
                Toast.makeText(activity, "导入失败，请使用 [00:12.34]歌词 格式的 .lrc 文件", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun deleteLyricsForCurrentMedia(media: CurrentMediaInfo, mode: LyricsStorage.DeleteMode) {
        val deleted = LyricsStorage.deleteLocalLyrics(
            context = activity,
            title = media.title,
            artist = media.artist,
            duration = media.durationMs,
            mode = mode
        )

        if (deleted) {
            val message = when (mode) {
                LyricsStorage.DeleteMode.PLAIN -> "已移除当前音乐的普通歌词"
                LyricsStorage.DeleteMode.KARAOKE -> "已移除当前音乐的逐字歌词"
                LyricsStorage.DeleteMode.ALL -> "已移除当前音乐的全部本地歌词"
            }
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            activity.reloadFloatingLyrics()
            activity.renderCurrentPage(animateContent = false, animateTabs = false)
        } else {
            val message = when (mode) {
                LyricsStorage.DeleteMode.PLAIN -> "当前音乐没有可移除的普通歌词"
                LyricsStorage.DeleteMode.KARAOKE -> "当前音乐没有可移除的逐字歌词"
                LyricsStorage.DeleteMode.ALL -> "当前音乐没有可移除的本地歌词"
            }
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
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
