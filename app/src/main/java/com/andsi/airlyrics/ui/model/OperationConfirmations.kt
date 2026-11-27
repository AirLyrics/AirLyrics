package com.andsi.airlyrics.ui.model

import com.andsi.airlyrics.core.model.SongIdentity

internal data class LyricsOperationTarget(val sourcePackage: String, val song: SongIdentity)

internal sealed interface ConfirmationAction {
    data class DeleteCurrent(val target: LyricsOperationTarget, val mode: LyricsDeleteMode) : ConfirmationAction
    data object DeleteAll : ConfirmationAction
    data class DeleteEditor(val sessionId: String) : ConfirmationAction
}

internal data class ConfirmationPrompt(
    val id: String,
    val title: String,
    val message: String,
    val positiveText: String
)

internal interface OperationConfirmations {
    val pending: ConfirmationPrompt?
    fun request(action: ConfirmationAction, title: String, message: String, positiveText: String)
    fun cancel(id: String)
    fun confirm(id: String)
}
