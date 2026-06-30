package com.andsi.airlyrics.media

import android.content.ComponentName
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.andsi.airlyrics.BuildConfig
import com.andsi.airlyrics.common.BroadcastActions
import com.andsi.airlyrics.settings.store.LanguageSettingsStore

class MediaNotificationListenerService : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var mediaSessionManager: MediaSessionManager? = null
    private val controllers = mutableMapOf<String, MediaController>()
    private val callbacks = mutableMapOf<String, MediaController.Callback>()

    private val activeSessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { setupMediaSessions(it) }

    private val rescanRunnable = Runnable {
        setupMediaSessions()
    }

    override fun onCreate() {
        LanguageSettingsStore.applyAppLocale(this)
        super.onCreate()
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (BuildConfig.DEBUG) Log.d(TAG, "Notification listener connected")
        setupMediaSessions()

        try {
            val component = ComponentName(this, MediaNotificationListenerService::class.java)
            mediaSessionManager?.addOnActiveSessionsChangedListener(
                activeSessionsChangedListener,
                component,
                handler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to listen active session changes", e)
        }
    }

    override fun onListenerDisconnected() {
        cleanupMediaSessions()
        super.onListenerDisconnected()
        requestNotificationListenerRebind()
    }

    override fun onDestroy() {
        cleanupMediaSessions()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        scheduleMediaSessionRescan()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        scheduleMediaSessionRescan()
    }

    private fun setupMediaSessions(activeSessions: List<MediaController>? = null) {
        try {
            val component = ComponentName(this, MediaNotificationListenerService::class.java)
            val sessions = activeSessions ?: mediaSessionManager?.getActiveSessions(component) ?: return
            val activePackages = sessions.map { it.packageName }.toSet()

            controllers.keys
                .filter { it !in activePackages }
                .forEach { packageName ->
                    callbacks.remove(packageName)?.let { callback ->
                        controllers[packageName]?.unregisterCallback(callback)
                    }
                    controllers.remove(packageName)
                    publishMediaSourceLost(packageName)
                }

            for (controller in sessions) {
                val packageName = controller.packageName
                if (controllers[packageName] === controller) {
                    publishMediaInfo(controller)
                    continue
                }

                callbacks.remove(packageName)?.let { oldCallback ->
                    controllers[packageName]?.unregisterCallback(oldCallback)
                }

                controllers[packageName] = controller

                val callback = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        publishMediaInfo(controller)
                    }

                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        publishMediaInfo(controller)
                    }

                    override fun onSessionDestroyed() {
                        callbacks.remove(packageName)
                        controllers.remove(packageName)
                        publishMediaSourceLost(packageName)
                    }
                }

                callbacks[packageName] = callback
                controller.registerCallback(callback, handler)
                publishMediaInfo(controller)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Need notification access permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup media sessions", e)
        }
    }

    private fun scheduleMediaSessionRescan() {
        // Some players refresh sessions only after notification changes, so scan once after the burst.
        handler.removeCallbacks(rescanRunnable)
        handler.postDelayed(rescanRunnable, MEDIA_SESSION_RESCAN_DELAY_MS)
    }

    private fun cleanupMediaSessions() {
        handler.removeCallbacksAndMessages(null)

        runCatching {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(activeSessionsChangedListener)
        }.onFailure { e ->
            Log.w(TAG, "Failed to remove active session listener", e)
        }

        clearControllers()
    }

    private fun clearControllers() {
        val lostPackages = controllers.keys.toList()
        callbacks.forEach { (packageName, callback) ->
            controllers[packageName]?.unregisterCallback(callback)
        }
        callbacks.clear()
        controllers.clear()
        lostPackages.forEach { publishMediaSourceLost(it) }
    }

    private fun requestNotificationListenerRebind() {
        val component = ComponentName(
            this,
            MediaNotificationListenerService::class.java
        )

        runCatching {
            requestRebind(component)
        }
    }

    private fun publishMediaSourceLost(packageName: String) {
        if (packageName.isBlank()) return

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "media source lost: source=$packageName")
        }

        val intent = Intent(BroadcastActions.MEDIA_SOURCE_LOST).apply {
            setPackage(this@MediaNotificationListenerService.packageName)
            putExtra(BroadcastActions.EXTRA_SOURCE_PACKAGE, packageName)
        }
        sendBroadcast(intent)
    }

    private fun publishMediaInfo(controller: MediaController) {
        val metadata = controller.metadata ?: return
        val state = controller.playbackState

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()

        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val position = CurrentMediaReader.estimatedPositionMs(state)
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING

        if (title.isNullOrBlank()) return

        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "media: source=${controller.packageName} title=$title artist=$artist " +
                        "duration=$duration position=$position playing=$isPlaying"
            )
        }

        val intent = Intent(BroadcastActions.MEDIA_UPDATE).apply {
            setPackage(packageName)
            putExtra("title", title)
            putExtra("artist", artist ?: "")
            putExtra("album", album)
            putExtra("duration", duration)
            putExtra("position", position)
            putExtra("isPlaying", isPlaying)
            putExtra(BroadcastActions.EXTRA_SOURCE_PACKAGE, controller.packageName)
        }

        sendBroadcast(intent)
    }

    companion object {
        private const val TAG = "MediaNotificationListenerService"
        private const val MEDIA_SESSION_RESCAN_DELAY_MS = 200L
    }
}
