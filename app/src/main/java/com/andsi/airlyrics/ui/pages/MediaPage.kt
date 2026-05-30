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
import com.andsi.airlyrics.i18n.tr

internal fun createMediaPage(activity: MainActivity, animateContent: Boolean = true): View  = with(activity) createMediaPage@ {
    val container = pageContainer(activity, animateChanges = animateContent)
    val controllers = getActiveMediaControllers().filter { it.metadata != null || it.playbackState != null }
    val selectedPackage = MediaSourceStore.getSelectedPackage(this)
    val selectedController = controllers.firstOrNull { it.packageName == selectedPackage }
        ?: controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        ?: controllers.firstOrNull()

    container.addView(
        sectionTitle(activity, 
            tr("媒体流", "Media"),
            tr("选择要跟随的播放器，AirLyrics 会从这里读取歌曲状态。", "Choose the player AirLyrics follows.")
        )
    )

    container.addView(
        card(activity) {
            addView(label(activity, tr("当前媒体", "Current media"), colorTextMuted))
            if (selectedController != null) {
                val title = selectedController.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    .orEmpty()
                    .ifBlank { tr("未知歌曲", "Unknown song") }
                val artist = selectedController.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: selectedController.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: tr("未知艺术家", "Unknown artist")
                val appName = getAppName(selectedController.packageName)
                val state = getPlaybackStateText(selectedController.playbackState?.state)

                addView(bigText(activity, title))
                addView(normalText(activity, "$artist · $appName"))
                addView(statusPill(activity, state, selectedController.playbackState?.state == PlaybackState.STATE_PLAYING))
            } else {
                addView(bigText(activity, tr("还没有检测到媒体", "No media detected yet")))
                addView(normalText(activity, tr("先开启通知访问权限，然后播放一首歌。", "Enable notification access, then play a song.")))
            }
        }
    )

    container.addView(spacer(activity, 12))
    container.addView(label(activity, tr("活跃播放器", "Active players"), colorTextMuted))

    if (controllers.isEmpty()) {
        container.addView(
            card(activity) {
                addView(bigText(activity, tr("等待音乐信号", "Waiting for music signal")))
                addView(normalText(activity, tr("播放音乐后，这里会显示可选择的媒体流。", "Playable media streams will appear here after music starts.")))
                addView(smallHint(activity, tr("如果一直没有显示，请确认通知访问权限已开启。", "If nothing appears, make sure notification access is enabled.")))
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
