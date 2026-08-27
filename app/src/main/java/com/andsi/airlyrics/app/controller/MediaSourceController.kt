package com.andsi.airlyrics.app.controller

import com.andsi.airlyrics.R

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.view.View
import androidx.annotation.StringRes
import com.andsi.airlyrics.app.contracts.FloatingSourceNotifier
import com.andsi.airlyrics.app.contracts.MediaControllerProvider
import com.andsi.airlyrics.app.contracts.MediaPageRefreshScheduler
import com.andsi.airlyrics.app.contracts.MediaSourceSelectionRenderer
import com.andsi.airlyrics.media.CurrentMediaBroadcast
import com.andsi.airlyrics.media.CurrentMediaReader
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.ui.components.playTinyPulse

internal class MediaSourceController(
    private val context: Context,
    private val mediaPageRefreshScheduler: MediaPageRefreshScheduler,
    private val sourceSelectionRenderer: MediaSourceSelectionRenderer,
    private val floatingSourceNotifier: FloatingSourceNotifier
) : MediaControllerProvider {
    fun handleMediaStatusBroadcast(intent: Intent) {
        if (CurrentMediaBroadcast.isMediaStatusIntent(intent)) {
            mediaPageRefreshScheduler.scheduleMediaPageRefresh()
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
        return CurrentMediaReader.getActiveControllers(context)
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
        return context.getString(playbackStateTextRes(state))
    }

    @StringRes
    private fun playbackStateTextRes(state: Int?): Int = when (state) {
        PlaybackState.STATE_PLAYING -> R.string.ui_playing
        PlaybackState.STATE_PAUSED -> R.string.ui_paused
        PlaybackState.STATE_STOPPED -> R.string.ui_stopped
        PlaybackState.STATE_BUFFERING -> R.string.ui_buffering
        PlaybackState.STATE_CONNECTING -> R.string.ui_connecting
        PlaybackState.STATE_FAST_FORWARDING -> R.string.ui_fast_forwarding
        PlaybackState.STATE_REWINDING -> R.string.ui_rewinding
        PlaybackState.STATE_SKIPPING_TO_NEXT -> R.string.ui_skipping_next
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> R.string.ui_skipping_previous
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> R.string.ui_skipping_queue
        PlaybackState.STATE_NONE -> R.string.ui_no_playback_state
        PlaybackState.STATE_ERROR -> R.string.ui_playback_error
        else -> R.string.ui_unknown_status
    }
}
