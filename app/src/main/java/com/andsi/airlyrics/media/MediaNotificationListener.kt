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

class MediaNotificationListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var mediaSessionManager: MediaSessionManager? = null
    private val controllers = mutableMapOf<String, MediaController>()
    private val callbacks = mutableMapOf<String, MediaController.Callback>()

    private val activeSessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { setupMediaSessions(it) }

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
            val component = ComponentName(this, MediaNotificationListener::class.java)
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
        super.onListenerDisconnected()
        mediaSessionManager?.removeOnActiveSessionsChangedListener(activeSessionsChangedListener)
        clearControllers()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)

        // Some players refresh sessions only after notification changes, so scan once with a delay.
        handler.postDelayed({
            setupMediaSessions()
        }, 200)
    }

    private fun setupMediaSessions(activeSessions: List<MediaController>? = null) {
        try {
            val component = ComponentName(this, MediaNotificationListener::class.java)
            val sessions = activeSessions ?: mediaSessionManager?.getActiveSessions(component) ?: return
            val activePackages = sessions.map { it.packageName }.toSet()

            controllers.keys
                .filter { it !in activePackages }
                .forEach { packageName ->
                    callbacks.remove(packageName)?.let { callback ->
                        controllers[packageName]?.unregisterCallback(callback)
                    }
                    controllers.remove(packageName)
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

    private fun clearControllers() {
        callbacks.forEach { (packageName, callback) ->
            controllers[packageName]?.unregisterCallback(callback)
        }
        callbacks.clear()
        controllers.clear()
    }

    private fun publishMediaInfo(controller: MediaController) {
        val metadata = controller.metadata ?: return
        val state = controller.playbackState

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()

        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val position = state?.position ?: 0L
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
        private const val TAG = "MediaNotificationListener"
    }
}
