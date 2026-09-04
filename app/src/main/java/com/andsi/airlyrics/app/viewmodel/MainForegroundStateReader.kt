package com.andsi.airlyrics.app.viewmodel

import android.content.Context
import android.media.MediaMetadata
import android.provider.Settings
import com.andsi.airlyrics.app.platform.PermissionHelper
import com.andsi.airlyrics.floating.FloatingWindowRuntimeState
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.media.CurrentMediaReader
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore

/** Reads platform and persisted sources into a stable main-screen snapshot. */
internal class MainForegroundStateReader(
    context: Context
) {
    private val appContext = context.applicationContext

    fun read(): ForegroundUiSnapshot {
        return ForegroundUiSnapshot(
            permissions = PermissionUiSnapshot(
                overlayGranted = Settings.canDrawOverlays(appContext),
                postNotificationsGranted =
                    PermissionHelper.hasPostNotificationsPermission(appContext),
                notificationListenerGranted =
                    PermissionHelper.hasNotificationListenerAccess(appContext),
                usageStatsGranted = PermissionHelper.hasUsageStatsAccess(appContext)
            ),
            floating = FloatingUiSnapshot(
                visible = FloatingWindowRuntimeState.snapshot()?.visible ?: false,
                desiredVisible = QuickFloatingStore.isDesiredVisible(appContext),
                locked = FloatingLyricsStyleStore.isLocked(appContext),
                clickThrough = FloatingLyricsStyleStore.isClickThrough(appContext)
            ),
            media = readMediaSnapshot(),
            lyricsRevision = LyricsStorage.currentRevision()
        )
    }

    private fun readMediaSnapshot(): MediaUiSnapshot {
        val controllers = CurrentMediaReader
            .selectedControllersByPackage(CurrentMediaReader.getActiveControllers(appContext))
            .values
            .toList()
        return MediaUiSnapshot(
            selectedPackage = MediaSourceStore.getSelectedPackage(appContext),
            players = controllers.map { controller ->
                MediaPlayerUiSnapshot(
                    packageName = controller.packageName,
                    title = controller.metadata
                        ?.getString(MediaMetadata.METADATA_KEY_TITLE)
                        .orEmpty(),
                    artist = controller.metadata
                        ?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        ?: controller.metadata
                            ?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                            .orEmpty(),
                    playbackState = controller.playbackState?.state
                )
            }
        )
    }
}
