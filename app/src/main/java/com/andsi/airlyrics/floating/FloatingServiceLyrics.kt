package com.andsi.airlyrics.floating

import android.net.Uri
import android.os.SystemClock
import com.andsi.airlyrics.R
import com.andsi.airlyrics.i18n.localizedLyricsLookupMessage
import com.andsi.airlyrics.i18n.localizedPlainLyricsSourceTitle
import com.andsi.airlyrics.lyrics.LyricsChange
import com.andsi.airlyrics.lyrics.LyricsChangeKind
import com.andsi.airlyrics.lyrics.LyricsLookupException
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.media.displayText
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.media.toSongIdentity
import com.andsi.airlyrics.settings.store.LyricsOffsetStore
import com.andsi.airlyrics.settings.store.LyricsSettingsStore

internal fun FloatingLyricsService.shouldSyncLyrics(): Boolean {
    return isWindowControllerReady() && windowController.isVisible
}

internal fun FloatingLyricsService.startLyricsSync() {
    syncHandler.removeCallbacks(syncRunnable)
    if (!shouldSyncLyrics()) return

    renderer.refresh()
    syncHandler.postDelayed(syncRunnable, lyricsSyncIntervalMs())
}

internal fun FloatingLyricsService.stopLyricsSync() {
    syncHandler.removeCallbacks(syncRunnable)
}

internal fun FloatingLyricsService.lyricsSyncIntervalMs(): Long {
    return if (renderer.isWordByWordActive()) 40L else 300L
}

internal fun FloatingLyricsService.applyLyricsOffset(offsetMs: Long) {
    if (renderer.setLyricsOffset(offsetMs)) {
        renderer.refresh()
    }
}

internal fun FloatingLyricsService.reloadCurrentLyrics() {
    if (currentMedia.isEmpty) {
        clearLyricsState(getString(R.string.ui_waiting_for_media_message))
        return
    }

    val playbackLyricsKey = currentMedia.playbackLyricsKey()
    val lookupRequestKey = currentMedia.lyricsLookupRequestKey(
        nonce = "reload|${SystemClock.uptimeMillis()}"
    )
    lastPlaybackLyricsKey = playbackLyricsKey
    activeLyricsLookupRequestKey = lookupRequestKey
    loadLyricsForSong(
        media = currentMedia,
        lookupRequestKey = lookupRequestKey
    )
}

internal fun FloatingLyricsService.handleLyricsChanged(change: LyricsChange) {
    if (currentMedia.isEmpty) return
    if (change.target?.let { !currentMedia.toSongIdentity().isSameSong(it) } == true) return

    val currentSong = currentMedia.toSongIdentity()
    automaticOnlineLookupSuppressedSong = when (change.kind) {
        LyricsChangeKind.DELETED -> currentSong
        LyricsChangeKind.UPDATED -> automaticOnlineLookupSuppressedSong
            ?.takeUnless(currentSong::isSameSong)
    }
    reloadCurrentLyrics()
}

internal fun FloatingLyricsService.clearLyricsState(message: String) {
    lastPlaybackLyricsKey = null
    automaticOnlineLookupSuppressedSong = null
    activeLyricsLookupRequestKey = null
    lyricsLookupRunner.cancelActive()
    currentMedia = CurrentMediaInfo.Empty
    renderer.setLyricsOffset(0L)
    renderer.clear()
    renderer.show(message)
}

internal fun FloatingLyricsService.loadLyricsForSong(
    media: CurrentMediaInfo,
    lookupRequestKey: LyricsLookupRequestKey,
) {
    val suppressAutomaticOnlineLookup = isAutomaticOnlineLookupSuppressed(media)
    val lookupSettings = LyricsSettingsStore.getSettings(this).let { settings ->
        if (suppressAutomaticOnlineLookup) {
            settings.copy(autoSearchOnline = false)
        } else {
            settings
        }
    }
    renderer.show(
        if (media.isPlaying) {
            "${getString(R.string.ui_searching_lyrics)}...\n${media.displayText}"
        } else {
            "${getString(R.string.ui_paused)}\n${media.displayText}"
        }
    )

    lyricsLookupRunner.submit(
        requestKey = lookupRequestKey.value,
        lookup = { token ->
            lookupLyricsForMedia(media, lookupSettings, token)
        },
        callback = { completedLookupRequestKey, result ->
            if (activeLyricsLookupRequestKey == LyricsLookupRequestKey(completedLookupRequestKey)) {
                activeLyricsLookupRequestKey = null
                applyLyricsResult(result = result, media = media)
            }
        }
    )
}

internal fun FloatingLyricsService.applyLyricsResult(
    result: Result<LyricsProviderResult?>,
    media: CurrentMediaInfo
) {
    val lyricsResult = result.getOrNull()
    val plainLrc = lyricsResult?.plainLrc

    if (plainLrc != null) {
        renderer.setLyricsOffset(LyricsOffsetStore.getOffsetMs(this, media.toSongIdentity()))
        renderer.parseAndShow(
            plainLrc = plainLrc,
            translatedLrc = lyricsResult.translatedLrc,
            wordByWordLines = lyricsResult.wordByWordLines,
            emptyText = getString(R.string.ui_parsed_lyrics_are_empty) + "\n" + media.displayText
        )
        return
    }

    renderer.clear()
    renderer.show(lookupFailureText(result.exceptionOrNull(), media))
}

internal fun FloatingLyricsService.lookupFailureText(error: Throwable?, media: CurrentMediaInfo): String {
    val lookupError = error as? LyricsLookupException
    return if (lookupError != null) {
        "${media.displayText}\n${localizedLyricsLookupMessage(lookupError)}"
    } else {
        notFoundText(media)
    }
}

internal fun FloatingLyricsService.notFoundText(media: CurrentMediaInfo): String {
    return if (isAutomaticOnlineLookupSuppressed(media)) {
        "${media.displayText}\n${getString(R.string.ui_lyrics_removed_auto_search_paused)}"
    } else if (!LyricsSettingsStore.isAutoSearchOnlineEnabled(this)) {
        "${getString(R.string.ui_using_local_lyrics_only)}\n${media.displayText}\n${getString(R.string.ui_local_file_not_found)}"
    } else {
        val plainLyricsSourceTitle =
            localizedPlainLyricsSourceTitle(LyricsSettingsStore.getPlainLyricsSearchSource(this))
        "${media.displayText}\n${getString(R.string.ui_source)}$plainLyricsSourceTitle\n${getString(R.string.ui_lyrics_not_found)}"
    }
}

internal fun FloatingLyricsService.importPlainLyrics(uri: Uri, overwrite: Boolean) {
    lyricsLookupRunner.cancelActive()
    activeLyricsLookupRequestKey = null
    val media = currentMedia

    if (media.title.isBlank()) {
        renderer.show(getString(R.string.ui_no_song_for_lyrics_binding))
        return
    }

    val imported = LyricsStorage.importPlainLyricsFromUri(
        context = this,
        uri = uri,
        title = media.title,
        artist = media.artist,
        duration = media.durationMs,
        album = media.album,
        overwrite = overwrite
    )

    if (!imported) {
        renderer.show(getString(R.string.ui_lyrics_import_failed))
        return
    }

    val localPlainLrc = LyricsStorage.readPlainLyrics(
        context = this,
        title = media.title,
        artist = media.artist,
        duration = media.durationMs
    )

    if (localPlainLrc != null) {
        automaticOnlineLookupSuppressedSong = null
        lastPlaybackLyricsKey = media.playbackLyricsKey()
        activeLyricsLookupRequestKey = null
        renderer.setLyricsOffset(LyricsOffsetStore.getOffsetMs(this, media.toSongIdentity()))
        renderer.parseAndShow(
            plainLrc = localPlainLrc,
            emptyText = getString(R.string.ui_lyrics_import_empty_error)
        )
    } else {
        renderer.show(getString(R.string.ui_lyrics_import_failed))
    }
}

internal fun FloatingLyricsService.isAutomaticOnlineLookupSuppressed(
    media: CurrentMediaInfo
): Boolean {
    return automaticOnlineLookupSuppressedSong
        ?.isSameSong(media.toSongIdentity()) == true
}
