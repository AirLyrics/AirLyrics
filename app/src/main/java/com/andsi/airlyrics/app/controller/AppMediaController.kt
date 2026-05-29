package com.andsi.airlyrics.app.controller

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.View
import android.widget.Toast
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.app.updateMediaSourceSelectionVisuals
import com.andsi.airlyrics.media.MediaNotificationListener
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.ui.components.playTinyPulse

internal class AppMediaController(
    private val activity: MainActivity
) {
    fun autoSelectSourceOnceIfNeeded() {
        if (MediaSourceStore.getSelectedPackage(activity) != null) return

        val controllers = getActiveControllers()
            .filter { it.metadata != null || it.playbackState != null }

        val controller = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull() ?: return

        MediaSourceStore.saveSelectedPackage(activity, controller.packageName)
    }

    fun getActiveControllers(): List<MediaController> {
        return try {
            val mediaSessionManager =
                activity.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(activity, MediaNotificationListener::class.java)
            mediaSessionManager.getActiveSessions(component)
        } catch (e: SecurityException) {
            Toast.makeText(activity, "需要先开启通知访问权限", Toast.LENGTH_LONG).show()
            emptyList()
        } catch (e: Exception) {
            Toast.makeText(activity, "读取媒体来源失败", Toast.LENGTH_LONG).show()
            emptyList()
        }
    }

    fun selectSource(packageName: String, sourceCard: View) {
        MediaSourceStore.saveSelectedPackage(activity, packageName)
        activity.notifyFloatingServiceSourceChangedIfVisible(packageName)
        activity.updateMediaSourceSelectionVisuals(packageName)
        playTinyPulse(sourceCard)
    }

    fun getAppName(packageName: String): String {
        return try {
            val appInfo = activity.packageManager.getApplicationInfo(packageName, 0)
            activity.packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    fun getPlaybackStateText(state: Int?): String {
        return when (state) {
            PlaybackState.STATE_PLAYING -> "播放中"
            PlaybackState.STATE_PAUSED -> "暂停中"
            PlaybackState.STATE_STOPPED -> "已停止"
            PlaybackState.STATE_BUFFERING -> "缓冲中"
            PlaybackState.STATE_CONNECTING -> "连接中"
            PlaybackState.STATE_FAST_FORWARDING -> "快进中"
            PlaybackState.STATE_REWINDING -> "快退中"
            PlaybackState.STATE_SKIPPING_TO_NEXT -> "切到下一首"
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "切到上一首"
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "切换队列"
            PlaybackState.STATE_NONE -> "无播放状态"
            PlaybackState.STATE_ERROR -> "播放异常"
            else -> "状态未知"
        }
    }
}
