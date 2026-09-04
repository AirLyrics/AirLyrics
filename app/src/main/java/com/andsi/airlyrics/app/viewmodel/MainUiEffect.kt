package com.andsi.airlyrics.app.viewmodel

import androidx.annotation.StringRes
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.LyricsLookupException

/** One-off Android UI work emitted by the retained main-screen state owner. */
internal sealed interface MainUiEffect {
    data object RequestOverlayPermission : MainUiEffect
    data object RequestNotificationPermission : MainUiEffect
    data object OpenNotificationListenerSettings : MainUiEffect
    data object OpenUsageAccessSettings : MainUiEffect
    data object SelectLyricsDirectory : MainUiEffect
    data object SelectLyricsFile : MainUiEffect
    data object SelectFloatingFontFile : MainUiEffect

    data class ShowMessage(
        @param:StringRes val messageRes: Int,
        val error: Boolean = false
    ) : MainUiEffect

    data class ShowImportFormatError(
        val invalidLineNumbers: List<Int>,
        val wordByWord: Boolean
    ) : MainUiEffect

    data class ShowLyricsLookupError(
        val error: LyricsLookupException
    ) : MainUiEffect

    data class ShowLyricsImportChoices(
        val target: SongIdentity,
        val plainImportEnabled: Boolean,
        val wordByWordImportEnabled: Boolean
    ) : MainUiEffect

    data class SavedLyricsDeletionCompleted(
        val requestId: Long,
        val deleted: Boolean
    ) : MainUiEffect

    data class FloatingFontImported(
        val displayName: String
    ) : MainUiEffect
}
