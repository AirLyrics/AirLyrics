package com.andsi.airlyrics.app.controller

import com.andsi.airlyrics.R

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.view.View
import com.andsi.airlyrics.app.contracts.FloatingSourceNotifier
import com.andsi.airlyrics.app.contracts.MediaControllerProvider
import com.andsi.airlyrics.app.contracts.MediaPageRefreshScheduler
import com.andsi.airlyrics.app.contracts.MediaSourceSelectionRenderer
import com.andsi.airlyrics.common.BroadcastActions
import com.andsi.airlyrics.media.MediaSnapshotReader
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.ui.components.playTinyPulse

internal class MediaSourceController(
    private val context: Context,
    private val mediaPageRefreshScheduler: MediaPageRefreshScheduler,
    private val sourceSelectionRenderer: MediaSourceSelectionRenderer,
    private val floatingSourceNotifier: FloatingSourceNotifier
) : MediaControllerProvider {
    fun handleMediaStatusBroadcast(intent: Intent) {
        when (intent.action) {
            BroadcastActions.MEDIA_UPDATE,
            BroadcastActions.MEDIA_SOURCE_LOST -> mediaPageRefreshScheduler.scheduleMediaPageRefresh()
        }
    }

    fun autoSelectSourceOnceIfNeeded() {
        if (MediaSourceStore.getSelectedPackage(context) != null) return

        val controllers = getActiveControllers()
            .filter { it.metadata != null || it.playbackState != null }

        val controller = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull() ?: return

        MediaSourceStore.saveSelectedPackage(context, controller.packageName)
    }

    override fun getActiveControllers(): List<MediaController> {
        return MediaSnapshotReader.getActiveControllers(context)
    }

    fun selectSource(packageName: String, sourceCard: View) {
        MediaSourceStore.saveSelectedPackage(context, packageName)
        floatingSourceNotifier.notifySourceChangedIfVisible(packageName)
        sourceSelectionRenderer.updateMediaSourceSelection(packageName)
        playTinyPulse(sourceCard)
    }

    fun getAppName(packageName: String): String {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    fun getPlaybackStateText(state: Int?): String {
        return when (state) {
            PlaybackState.STATE_PLAYING -> context.getString(R.string.ui_playing)
            PlaybackState.STATE_PAUSED -> context.getString(R.string.ui_paused)
            PlaybackState.STATE_STOPPED -> context.getString(R.string.ui_stopped)
            PlaybackState.STATE_BUFFERING -> context.getString(R.string.ui_buffering)
            PlaybackState.STATE_CONNECTING -> context.getString(R.string.ui_connecting)
            PlaybackState.STATE_FAST_FORWARDING -> context.getString(R.string.ui_fast_forwarding)
            PlaybackState.STATE_REWINDING -> context.getString(R.string.ui_rewinding)
            PlaybackState.STATE_SKIPPING_TO_NEXT -> context.getString(R.string.ui_skipping_next)
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> context.getString(R.string.ui_skipping_previous)
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> context.getString(R.string.ui_skipping_queue)
            PlaybackState.STATE_NONE -> context.getString(R.string.ui_no_playback_state)
            PlaybackState.STATE_ERROR -> context.getString(R.string.ui_playback_error)
            else -> context.getString(R.string.ui_unknown_status)
        }
    }
}
