package com.andsi.airlyrics.app.controller

import com.andsi.airlyrics.R

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.View
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
            emptyList()
        } catch (e: Exception) {
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
            PlaybackState.STATE_PLAYING -> activity.getString(R.string.ui_playing)
            PlaybackState.STATE_PAUSED -> activity.getString(R.string.ui_paused)
            PlaybackState.STATE_STOPPED -> activity.getString(R.string.ui_stopped)
            PlaybackState.STATE_BUFFERING -> activity.getString(R.string.ui_buffering)
            PlaybackState.STATE_CONNECTING -> activity.getString(R.string.ui_connecting)
            PlaybackState.STATE_FAST_FORWARDING -> activity.getString(R.string.ui_fast_forwarding)
            PlaybackState.STATE_REWINDING -> activity.getString(R.string.ui_rewinding)
            PlaybackState.STATE_SKIPPING_TO_NEXT -> activity.getString(R.string.ui_skipping_next)
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> activity.getString(R.string.ui_skipping_previous)
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> activity.getString(R.string.ui_skipping_queue)
            PlaybackState.STATE_NONE -> activity.getString(R.string.ui_no_playback_state)
            PlaybackState.STATE_ERROR -> activity.getString(R.string.ui_playback_error)
            else -> activity.getString(R.string.ui_unknown_status)
        }
    }
}
