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
    internal data class ControllerCandidate<T>(
        val value: T,
        val packageName: String,
        val hasMetadata: Boolean,
        val hasPlaybackState: Boolean,
        val hasMediaTitle: Boolean,
        val isPlaying: Boolean
    )

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
        return selectedCandidate(controllers.map { it.toCandidate() }, selectedPackage)?.value
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
        return bestCandidate(controllers.map { it.toCandidate() }, selectedPackage)?.value
    }

    fun currentMediaFromController(controller: MediaController): CurrentMediaInfo? {
        return controller.toCurrentMediaInfo()
    }

    private fun MediaController.hasMediaTitle(): Boolean {
        return metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.isNotBlank()
            ?: false
    }

    private fun MediaController.toCandidate(): ControllerCandidate<MediaController> {
        return ControllerCandidate(
            value = this,
            packageName = packageName,
            hasMetadata = metadata != null,
            hasPlaybackState = playbackState != null,
            hasMediaTitle = hasMediaTitle(),
            isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
        )
    }

    internal fun <T> selectedCandidate(
        candidates: List<ControllerCandidate<T>>,
        selectedPackage: String?
    ): ControllerCandidate<T>? {
        if (selectedPackage.isNullOrBlank()) return null

        val selectedCandidates = candidates.filter {
            it.packageName == selectedPackage &&
                (it.hasMetadata || it.hasPlaybackState)
        }

        return selectedCandidates.firstOrNull { it.hasMediaTitle && it.isPlaying }
            ?: selectedCandidates.firstOrNull { it.hasMediaTitle }
            ?: selectedCandidates.firstOrNull { it.isPlaying }
            ?: selectedCandidates.firstOrNull { it.hasMetadata }
            ?: selectedCandidates.firstOrNull()
    }

    internal fun <T> bestCandidate(
        candidates: List<ControllerCandidate<T>>,
        selectedPackage: String?
    ): ControllerCandidate<T>? {
        val usableCandidates = candidates.filter { it.hasMetadata || it.hasPlaybackState }
        return selectedCandidate(usableCandidates, selectedPackage)
            ?: usableCandidates.firstOrNull { it.hasMediaTitle && it.isPlaying }
            ?: usableCandidates.firstOrNull { it.hasMediaTitle }
            ?: usableCandidates.firstOrNull { it.isPlaying }
            ?: usableCandidates.firstOrNull { it.hasMetadata }
            ?: usableCandidates.firstOrNull()
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
