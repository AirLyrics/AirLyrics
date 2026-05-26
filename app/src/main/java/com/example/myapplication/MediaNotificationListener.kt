package com.example.myapplication

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

class MediaNotificationListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var mediaSessionManager: MediaSessionManager? = null
    private val controllers = mutableMapOf<String, MediaController>()

    override fun onCreate() {
        super.onCreate()
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected")
        setupMediaSessions()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        controllers.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)

        // 有些播放器只有通知变化时才刷新 session，所以这里延迟扫一次
        handler.postDelayed({
            setupMediaSessions()
        }, 200)
    }

    private fun setupMediaSessions() {
        try {
            val component = ComponentName(this, MediaNotificationListener::class.java)
            val activeSessions = mediaSessionManager?.getActiveSessions(component) ?: return

            controllers.clear()

            for (controller in activeSessions) {
                val packageName = controller.packageName
                controllers[packageName] = controller

                controller.registerCallback(object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        publishMediaInfo(controller)
                    }

                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        publishMediaInfo(controller)
                    }
                }, handler)

                publishMediaInfo(controller)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Need notification access permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup media sessions", e)
        }
    }

    private fun publishMediaInfo(controller: MediaController) {
        val metadata = controller.metadata ?: return
        val state = controller.playbackState

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)

        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val position = state?.position ?: 0L
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING

        if (title.isNullOrBlank()) return

        Log.d(TAG, "media: title=$title artist=$artist duration=$duration position=$position playing=$isPlaying")

        val intent = Intent(FloatingLyricsService.ACTION_MEDIA_UPDATE).apply {
            setPackage(packageName)
            putExtra("title", title)
            putExtra("artist", artist ?: "")
            putExtra("duration", duration)
            putExtra("position", position)
            putExtra("isPlaying", isPlaying)
            putExtra("sourcePackage", controller.packageName)
        }

        sendBroadcast(intent)
    }

    companion object {
        private const val TAG = "MediaNotificationListener"
    }
}
