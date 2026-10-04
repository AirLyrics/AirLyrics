package com.andsi.airlyrics.ui.state

import android.os.Bundle
import com.andsi.airlyrics.R
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.ui.components.showAirDialog
import com.andsi.airlyrics.ui.model.MainUiHost
import java.util.UUID

internal enum class ConfirmationOperation { DELETE_CURRENT, DELETE_ALL, SEARCH, DELETE_EDITOR }

internal fun MainUiHost.confirmOperation(
    operation: ConfirmationOperation,
    title: String,
    message: String,
    positiveText: String,
    media: CurrentMediaInfo? = null,
    mode: String? = null
) {
    val request = Bundle().apply {
        putString("id", UUID.randomUUID().toString())
        putString("operation", operation.name)
        putString("title", title)
        putString("message", message)
        putString("positive", positiveText)
        putString("mode", mode)
        putLong("revision", LyricsStorage.currentRevision())
        putString("process", LyricsStorage.processGeneration)
        if (operation == ConfirmationOperation.DELETE_EDITOR) putString("editor", editorSession.state.value?.id)
        media?.let {
            putBundle("media", Bundle().apply {
                putString("source", it.sourcePackage)
                putString("title", it.title)
                putString("artist", it.artist)
                putString("album", it.album)
                putLong("duration", it.durationMs)
            })
        }
    }
    interactions.write("confirmation") { clear(); putAll(request) }
    restoreOperationConfirmation()
}

internal fun Bundle.confirmationMedia(): CurrentMediaInfo? = getBundle("media")?.let {
    CurrentMediaInfo(it.getString("source").orEmpty(), it.getString("title").orEmpty(),
        it.getString("artist").orEmpty(), it.getString("album").orEmpty(), it.getLong("duration"), false, 0L)
}

internal fun MainUiHost.restoreOperationConfirmation() {
    val request = interactions.read("confirmation") ?: return
    val id = request.getString("id") ?: return
    if (activeConfirmationId == id) return
    val operation = ConfirmationOperation.entries.find { it.name == request.getString("operation") } ?: return
    if (operation == ConfirmationOperation.DELETE_EDITOR && editorSession.state.value?.id != request.getString("editor")) return
    activeConfirmationId = id
    showAirDialog(
        title = request.getString("title"), message = request.getString("message"),
        positiveText = request.getString("positive"), negativeText = getString(R.string.ui_cancel),
        onUserDismiss = {
            if (interactions.read("confirmation")?.getString("id") == id) interactions.remove("confirmation")
            if (activeConfirmationId == id) activeConfirmationId = null
        },
        onPositive = {
            if (interactions.read("confirmation")?.getString("id") == id) {
                interactions.remove("confirmation")
                activeConfirmationId = null
                if (operation == ConfirmationOperation.DELETE_EDITOR) {
                    if (editorSession.state.value?.id == request.getString("editor")) editorSession.delete()
                } else executeConfirmedOperation(request)
            }
        }
    )
}
