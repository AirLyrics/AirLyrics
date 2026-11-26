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
        QuickFloatingStore.isDesiredVisible(this) &&
        (windowController.isVisible || autoHideState.shouldHide || autoHiddenForDisplayScope) &&
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

    val song = media.toSongIdentity()
    if (currentMedia.isEmpty || !currentMedia.toSongIdentity().isSameSong(song)) {
        playbackClock.reset()
    }
    currentMedia = media
    playbackClock.update(media.positionMs, media.isPlaying)

    syncHandler.removeCallbacks(mediaRestoreRunnable)
    mediaRestoreAttempt = 0

    renderer.setLyricsOffset(LyricsOffsetStore.getOffsetMs(this, song))
    renderer.updateSpicyPlayback()
    reevaluateAutoHide()

    val playbackLyricsKey = media.playbackLyricsKey()
    if (automaticOnlineLookupSuppressedSong != null &&
        !isAutomaticOnlineLookupSuppressed(media)
    ) {
        automaticOnlineLookupSuppressedSong = null
    }
    val samePlayback = playbackLyricsKey == lastPlaybackLyricsKey
    val idArrived = media.spotifyTrackId != null && media.spotifyTrackId != lastLookupSpotifyTrackId
    val retryWithId = samePlayback && idArrived &&
        autoHideState.lyricsAvailability != LyricsAvailability.AVAILABLE &&
        !isAutomaticOnlineLookupSuppressed(media) &&
        com.andsi.airlyrics.settings.store.LyricsSettingsStore.isAutoSearchOnlineEnabled(this)
    if (samePlayback && !retryWithId) {
        renderer.tick()
        scheduleNextLyricsSync()
        return true
    }
    lastLookupSpotifyTrackId = media.spotifyTrackId

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
        listOfNotNull(playbackKey.value, spotifyTrackId?.let { "spotify:$it" }, nonce).joinToString("|")
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

    currentMedia = currentMedia.copy(
        isPlaying = false,
        positionMs = playbackClock.pause()
    )
    renderer.refresh()
    reevaluateAutoHide()
}

internal fun FloatingLyricsService.syncSelectedMediaSource() {
    val packageName = MediaSourceStore.getSelectedPackage(this)
    if (selectedSourcePackage == packageName) return

    selectedSourcePackage = packageName
    syncHandler.removeCallbacks(mediaRestoreRunnable)
    mediaRestoreAttempt = 0
    currentMedia = CurrentMediaInfo.Empty
    playbackClock.reset()
    renderer.setLyricsOffset(0L)

    clearLyricsState {
        getString(
            if (packageName == null) {
                R.string.ui_no_media_source_status
            } else {
                R.string.ui_media_source_waiting_status
            }
        )
    }

    startSelectedMediaObservation()
    if (packageName != null && isWindowControllerReady() &&
        windowController.isVisible && currentMedia.isEmpty
    ) {
        scheduleCurrentMediaRestore()
    }
}

private fun FloatingLyricsService.shouldAcceptMediaUpdate(sourcePackage: String): Boolean {
    if (sourcePackage.isBlank()) return false

    val selectedPackage = selectedSourcePackage ?: return false
    return sourcePackage == selectedPackage
}
