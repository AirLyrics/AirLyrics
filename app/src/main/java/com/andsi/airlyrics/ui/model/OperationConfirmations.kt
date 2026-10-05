package com.andsi.airlyrics.ui.model

import com.andsi.airlyrics.core.model.SongIdentity

/** Immutable target captured with the displayed song, independent of live playback state. */
internal data class LyricsOperationTarget(val sourcePackage: String, val song: SongIdentity)

internal sealed interface ConfirmationAction {
    data class DeleteCurrent(val target: LyricsOperationTarget, val mode: LyricsDeleteMode) : ConfirmationAction
    data object DeleteAll : ConfirmationAction
    data class Search(val target: LyricsOperationTarget) : ConfirmationAction
    data class DeleteEditor(val sessionId: String) : ConfirmationAction
}

internal data class ConfirmationPrompt(
    val id: String,
    val title: String,
    val message: String,
    val positiveText: String
)

/** The application owns the saved request and consumes each confirmed operation at most once. */
internal interface OperationConfirmations {
    val pending: ConfirmationPrompt?
    fun request(action: ConfirmationAction, title: String, message: String, positiveText: String)
    fun cancel(id: String)
    fun confirm(id: String)
}
