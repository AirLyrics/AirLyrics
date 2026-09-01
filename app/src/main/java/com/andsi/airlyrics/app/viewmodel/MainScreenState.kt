package com.andsi.airlyrics.app.viewmodel

import com.andsi.airlyrics.app.state.PendingLyricsImport
import com.andsi.airlyrics.app.state.PendingLyricsOverwrite
import com.andsi.airlyrics.ui.model.MainUiState
import com.andsi.airlyrics.ui.model.RefreshState
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage

internal data class PermissionUiSnapshot(
    val overlayGranted: Boolean = false,
    val postNotificationsGranted: Boolean = false,
    val notificationListenerGranted: Boolean = false
)

internal data class FloatingUiSnapshot(
    val visible: Boolean = false,
    val locked: Boolean = false,
    val clickThrough: Boolean = false
)

internal data class MediaPlayerUiSnapshot(
    val packageName: String,
    val title: String,
    val artist: String,
    val playbackState: Int?
)

internal data class MediaUiSnapshot(
    val selectedPackage: String? = null,
    val players: List<MediaPlayerUiSnapshot> = emptyList()
)

internal data class ForegroundUiSnapshot(
    val permissions: PermissionUiSnapshot = PermissionUiSnapshot(),
    val floating: FloatingUiSnapshot = FloatingUiSnapshot(),
    val media: MediaUiSnapshot = MediaUiSnapshot(),
    val lyricsRevision: Long = 0L
)

/** Immutable state for the logical main screen, retained across Activity recreation. */
internal data class MainScreenState(
    override val currentPage: Page = Page.MEDIA,
    override val settingsSubPage: SettingsSubPage = SettingsSubPage.HOME,
    override val savedLyricsSearchOpen: Boolean = false,
    override val savedLyricsSearchQuery: String = "",
    override val mediaRefreshState: RefreshState = RefreshState.IDLE,
    val foreground: ForegroundUiSnapshot = ForegroundUiSnapshot(),
    val lyricsChangeSequence: Long = 0L,
    val lyricsDirectoryRevision: Long = 0L,
    val floatingStructureRevision: Long = 0L,
    val pendingLyricsImport: PendingLyricsImport? = null,
    val pendingLyricsOverwrite: PendingLyricsOverwrite? = null
) : MainUiState {
    override val locked: Boolean
        get() = foreground.floating.locked

    override val clickThrough: Boolean
        get() = foreground.floating.clickThrough

    override val quickFloatingVisible: Boolean
        get() = foreground.floating.visible

    override val overlayPermissionGranted: Boolean
        get() = foreground.permissions.overlayGranted

    override val postNotificationsGranted: Boolean
        get() = foreground.permissions.postNotificationsGranted

    override val notificationListenerGranted: Boolean
        get() = foreground.permissions.notificationListenerGranted
}
