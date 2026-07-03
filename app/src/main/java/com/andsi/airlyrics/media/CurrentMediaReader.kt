package com.andsi.airlyrics.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.media.model.MediaSnapshotSequencer

object CurrentMediaReader {
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

    fun readSelectedCurrentMedia(context: Context, selectedPackage: String?): CurrentMediaInfo? {
        if (selectedPackage.isNullOrBlank()) return null

        return selectedController(getActiveControllers(context), selectedPackage)
            ?.toCurrentMediaInfo()
    }

    fun selectedController(
        controllers: List<MediaController>,
        selectedPackage: String?
    ): MediaController? {
        if (selectedPackage.isNullOrBlank()) return null

        val selectedControllers = controllers.filter {
            it.packageName == selectedPackage &&
                (it.metadata != null || it.playbackState != null)
        }

        return selectedControllers.firstOrNull {
            it.hasMediaTitle() && it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: selectedControllers.firstOrNull { it.hasMediaTitle() }
            ?: selectedControllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: selectedControllers.firstOrNull { it.metadata != null }
            ?: selectedControllers.firstOrNull()
    }

    fun selectedControllersByPackage(
        controllers: List<MediaController>
    ): Map<String, MediaController> {
        return controllers
            .filter { it.packageName.isNotBlank() }
            .groupBy { it.packageName }
            .mapNotNull { (packageName, packageControllers) ->
                selectedController(packageControllers, packageName)?.let { packageName to it }
            }
            .toMap()
    }

    fun bestController(
        controllers: List<MediaController>,
        selectedPackage: String?
    ): MediaController? {
        val usableControllers = controllers.filter { it.metadata != null || it.playbackState != null }
        return selectedController(usableControllers, selectedPackage)
            ?: usableControllers.firstOrNull {
                it.hasMediaTitle() && it.playbackState?.state == PlaybackState.STATE_PLAYING
            }
            ?: usableControllers.firstOrNull { it.hasMediaTitle() }
            ?: usableControllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: usableControllers.firstOrNull { it.metadata != null }
            ?: usableControllers.firstOrNull()
    }

    fun bestCurrentMediaFromControllers(
        controllers: List<MediaController>,
        selectedPackage: String?
    ): CurrentMediaInfo? {
        val controller = bestController(controllers, selectedPackage)
            ?: return null

        return controller.toCurrentMediaInfo()
    }

    fun currentMediaFromController(controller: MediaController): CurrentMediaInfo? {
        return controller.toCurrentMediaInfo()
    }

    private fun MediaController.hasMediaTitle(): Boolean {
        return metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.isNotBlank()
            ?: false
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
            positionMs = estimatedPositionMs(state),
            snapshotSequence = MediaSnapshotSequencer.next()
        )
    }

    fun estimatedPositionMs(state: PlaybackState?): Long {
        return estimatedPositionMs(state, SystemClock.elapsedRealtime())
    }

    internal fun estimatedPositionMs(state: PlaybackState?, elapsedRealtimeMs: Long): Long {
        if (state == null || state.position == PlaybackState.PLAYBACK_POSITION_UNKNOWN) {
            return 0L
        }

        val basePositionMs = state.position.coerceAtLeast(0L)
        if (state.state != PlaybackState.STATE_PLAYING || state.lastPositionUpdateTime <= 0L) {
            return basePositionMs
        }

        val elapsedMs = (elapsedRealtimeMs - state.lastPositionUpdateTime)
            .coerceAtLeast(0L)
        val speed = state.playbackSpeed.takeIf { it > 0f } ?: 1f
        return (basePositionMs + (elapsedMs * speed).toLong()).coerceAtLeast(0L)
    }
}
