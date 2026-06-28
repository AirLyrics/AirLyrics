package com.andsi.airlyrics.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.andsi.airlyrics.floating.model.CurrentMediaInfo

object MediaSnapshotReader {
    fun getActiveControllers(context: Context): List<MediaController> {
        return try {
            val mediaSessionManager =
                context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(context, MediaNotificationListenerService::class.java)
            mediaSessionManager.getActiveSessions(component)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun readSelected(context: Context, selectedPackage: String?): CurrentMediaInfo? {
        if (selectedPackage.isNullOrBlank()) return null

        return getActiveControllers(context)
            .firstOrNull { it.packageName == selectedPackage }
            ?.toCurrentMediaInfo()
    }

    fun bestFromControllers(
        controllers: List<MediaController>,
        selectedPackage: String?
    ): CurrentMediaInfo? {
        val usableControllers = controllers.filter { it.metadata != null || it.playbackState != null }
        val controller = selectedPackage
            ?.let { packageName -> usableControllers.firstOrNull { it.packageName == packageName } }
            ?: usableControllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: usableControllers.firstOrNull()
            ?: return null

        return controller.toCurrentMediaInfo()
    }

    fun MediaController.toCurrentMediaInfo(): CurrentMediaInfo? {
        val metadata = this.metadata ?: return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().trim()
        if (title.isBlank()) return null

        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val state = this.playbackState

        return CurrentMediaInfo(
            sourcePackage = this.packageName,
            title = title,
            artist = artist,
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
            isPlaying = state?.state == PlaybackState.STATE_PLAYING,
            positionMs = state?.position ?: 0L
        )
    }
}
