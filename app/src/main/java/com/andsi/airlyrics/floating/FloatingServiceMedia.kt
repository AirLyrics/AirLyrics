package com.andsi.airlyrics.floating

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.media.CurrentMediaReader
import com.andsi.airlyrics.media.MediaNotificationListenerService
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.media.toSongIdentity
import com.andsi.airlyrics.settings.store.LyricsOffsetStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore

internal fun FloatingLyricsService.shouldObserveSelectedMedia(): Boolean {
    return isWindowControllerReady() &&
        (windowController.isVisible ||
            (autoHiddenForPause && QuickFloatingStore.isDesiredVisible(this))) &&
        !selectedSourcePackage.isNullOrBlank()
}

internal fun FloatingLyricsService.startSelectedMediaObservation() {
    if (!shouldObserveSelectedMedia()) {
        stopSelectedMediaObservation()
        return
    }

    refreshSelectedCurrentMediaInfo()
    scheduleSelectedCurrentMediaInfoRefresh()
}

internal fun FloatingLyricsService.stopSelectedMediaObservation() {
    syncHandler.removeCallbacks(currentMediaRefreshRunnable)
}

internal fun FloatingLyricsService.scheduleSelectedCurrentMediaInfoRefresh() {
    syncHandler.removeCallbacks(currentMediaRefreshRunnable)
    if (shouldObserveSelectedMedia()) {
        syncHandler.postDelayed(
            currentMediaRefreshRunnable,
            FloatingLyricsService.CURRENT_MEDIA_REFRESH_INTERVAL_MS
        )
    }
}

internal fun FloatingLyricsService.refreshSelectedCurrentMediaInfo() {
    if (!shouldObserveSelectedMedia()) return

    val media = readSelectedCurrentMediaInfo()
    if (media != null) {
        applyCurrentMediaInfo(media)
    } else {
        selectedSourcePackage?.let(::handleMediaSourceLost)
    }
}

internal fun FloatingLyricsService.applyCurrentMediaInfo(media: CurrentMediaInfo): Boolean {
    if (media.title.isBlank()) return false
    if (!shouldAcceptMediaUpdate(media.sourcePackage)) return false
    if (!mediaSnapshotGate.markAcceptedIfFresh(media)) return false

    currentMedia = media

    syncHandler.removeCallbacks(mediaRestoreRunnable)
    mediaRestoreAttempt = 0

    renderer.updatePlayback(
        positionMs = media.positionMs,
        isPlaying = media.isPlaying
    )
    renderer.setLyricsOffset(LyricsOffsetStore.getOffsetMs(this, media.toSongIdentity()))
    applyAutoHideWhenPaused()

    val playbackLyricsKey = media.playbackLyricsKey()
    if (automaticOnlineLookupSuppressedSong != null &&
        !isAutomaticOnlineLookupSuppressed(media)
    ) {
        automaticOnlineLookupSuppressedSong = null
    }
    if (playbackLyricsKey == lastPlaybackLyricsKey) {
        renderer.tick()
        return true
    }

    val lookupRequestKey = media.lyricsLookupRequestKey()
    lastPlaybackLyricsKey = playbackLyricsKey
    activeLyricsLookupRequestKey = lookupRequestKey
    loadLyricsForSong(media = media, lookupRequestKey = lookupRequestKey)

    return true
}

internal fun CurrentMediaInfo.playbackLyricsKey(): PlaybackLyricsKey {
    val songKey = toSongIdentity().storageKey()
    val normalizedAlbum = SongIdentity.normalizeText(album)
    return PlaybackLyricsKey("$sourcePackage|$songKey|$normalizedAlbum")
}

internal fun CurrentMediaInfo.lyricsLookupRequestKey(nonce: String? = null): LyricsLookupRequestKey {
    val playbackKey = playbackLyricsKey()
    return LyricsLookupRequestKey(
        if (nonce == null) playbackKey.value else "${playbackKey.value}|$nonce"
    )
}

internal fun FloatingLyricsService.scheduleCurrentMediaRestore() {
    syncHandler.removeCallbacks(mediaRestoreRunnable)
    mediaRestoreAttempt = 0
    syncHandler.post(mediaRestoreRunnable)
}

internal fun FloatingLyricsService.restoreCurrentMediaOrRetry() {
    if (!currentMedia.isEmpty) return
    if (!QuickFloatingStore.isDesiredVisible(this)) return
    if (!isWindowControllerReady() || !windowController.isVisible) return
    if (selectedSourcePackage.isNullOrBlank()) return

    val restored = readSelectedCurrentMediaInfo()
        ?.let(::applyCurrentMediaInfo)
        ?: false

    if (restored) return

    if (mediaRestoreAttempt == 0) {
        requestNotificationListenerRebind()
    }

    val delay = FloatingLyricsService.MEDIA_RESTORE_RETRY_DELAYS_MS
        .getOrNull(mediaRestoreAttempt++)
        ?: return

    syncHandler.postDelayed(mediaRestoreRunnable, delay)
}

internal fun FloatingLyricsService.readSelectedCurrentMediaInfo(): CurrentMediaInfo? {
    return CurrentMediaReader.readSelectedCurrentMedia(
        context = this,
        selectedPackage = selectedSourcePackage
    )
}

internal fun FloatingLyricsService.requestNotificationListenerRebind() {
    val component = ComponentName(
        this,
        MediaNotificationListenerService::class.java
    )

    runCatching {
        NotificationListenerService.requestRebind(component)
    }
}

internal fun FloatingLyricsService.handleMediaSourceLost(sourcePackage: String) {
    if (sourcePackage.isBlank()) return
    if (sourcePackage != selectedSourcePackage) return
    if (currentMedia.isEmpty || currentMedia.sourcePackage != sourcePackage) return

    val pausedPosition = renderer.getEstimatedPositionMs()
    currentMedia = currentMedia.copy(
        isPlaying = false,
        positionMs = pausedPosition
    )
    renderer.updatePlayback(positionMs = pausedPosition, isPlaying = false)
    renderer.refresh()
    applyAutoHideWhenPaused()
}

internal fun FloatingLyricsService.selectMediaSource(packageName: String?) {
    selectedSourcePackage = packageName
    MediaSourceStore.saveSelectedPackage(this, packageName)
    syncHandler.removeCallbacks(mediaRestoreRunnable)
    mediaRestoreAttempt = 0

    clearLyricsState(
        getString(
            if (packageName == null) {
                R.string.ui_no_media_source_status
            } else {
                R.string.ui_media_source_waiting_status
            }
        )
    )

    if (packageName == null) {
        stopSelectedMediaObservation()
        return
    }

    if (isWindowControllerReady() && windowController.isVisible) {
        startSelectedMediaObservation()
        if (currentMedia.isEmpty) {
            scheduleCurrentMediaRestore()
        }
    }
}

private fun FloatingLyricsService.shouldAcceptMediaUpdate(sourcePackage: String): Boolean {
    if (sourcePackage.isBlank()) return false

    val selectedPackage = selectedSourcePackage ?: return false
    return sourcePackage == selectedPackage
}
