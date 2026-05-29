package com.andsi.airlyrics.ui.pages

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.view.View
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.app.refreshMediaButton
import com.andsi.airlyrics.app.mediaSourceCard
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.floating.FloatingLyricsService
import com.andsi.airlyrics.ui.components.*
import com.andsi.airlyrics.ui.theme.*

internal fun createMediaPage(activity: MainActivity, animateContent: Boolean = true): View  = with(activity) createMediaPage@ {
    val container = pageContainer(activity, animateChanges = animateContent)
    val controllers = getActiveMediaControllers().filter { it.metadata != null || it.playbackState != null }
    val selectedPackage = MediaSourceStore.getSelectedPackage(this)
    val selectedController = controllers.firstOrNull { it.packageName == selectedPackage }
        ?: controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        ?: controllers.firstOrNull()

    container.addView(
        sectionTitle(activity, 
            "媒体流",
            "选择要跟随的播放器，AirLyrics 会从这里读取歌曲状态。"
        )
    )

    container.addView(
        card(activity) {
            addView(label(activity, "当前媒体", colorTextMuted))
            if (selectedController != null) {
                val title = selectedController.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    .orEmpty()
                    .ifBlank { "未知歌曲" }
                val artist = selectedController.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: selectedController.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: "未知艺术家"
                val appName = getAppName(selectedController.packageName)
                val state = getPlaybackStateText(selectedController.playbackState?.state)

                addView(bigText(activity, title))
                addView(normalText(activity, "$artist · $appName"))
                addView(statusPill(activity, state, selectedController.playbackState?.state == PlaybackState.STATE_PLAYING))
            } else {
                addView(bigText(activity, "还没有检测到媒体"))
                addView(normalText(activity, "先开启通知访问权限，然后播放一首歌。"))
            }
        }
    )

    container.addView(spacer(activity, 12))
    container.addView(label(activity, "活跃播放器", colorTextMuted))

    if (controllers.isEmpty()) {
        container.addView(
            card(activity) {
                addView(bigText(activity, "等待音乐信号"))
                addView(normalText(activity, "播放音乐后，这里会显示可选择的媒体流。"))
                addView(smallHint(activity, "如果一直没有显示，请确认通知访问权限已开启。"))
            }
        )
    } else {
        controllers.forEach { controller ->
            container.addView(mediaSourceCard(controller, controller.packageName == selectedPackage))
        }
    }

    container.addView(spacer(activity, 18))
    container.addView(refreshMediaButton())

    return scroll(activity, container, animateChildren = animateContent)
}
