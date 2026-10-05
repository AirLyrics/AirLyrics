package com.andsi.airlyrics.app.interaction

import android.os.Bundle
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.ui.model.ConfirmationAction
import com.andsi.airlyrics.ui.model.ConfirmationPrompt
import com.andsi.airlyrics.ui.model.LyricsDeleteMode
import com.andsi.airlyrics.ui.model.LyricsOperationTarget
import com.andsi.airlyrics.ui.model.OperationConfirmations
import com.andsi.airlyrics.ui.state.MainInteractionState
import java.util.UUID

/** Owns the persisted business request; the UI only sees the prompt and its identity. */
internal class MainOperationConfirmations(
    private val interactions: MainInteractionState,
    private val editorSessionId: () -> String?,
    private val deleteAll: (Bundle) -> Unit,
    private val deleteCurrent: (CurrentMediaInfo, LyricsStorage.DeleteMode) -> Unit,
    private val search: (CurrentMediaInfo) -> Unit,
    private val deleteEditor: () -> Unit
) : OperationConfirmations {
    override val pending: ConfirmationPrompt?
        get() {
            val request = interactions.read(KEY) ?: return null
            val action = request.action() ?: return null
            if (action is ConfirmationAction.DeleteEditor && action.sessionId != editorSessionId()) return null
            return ConfirmationPrompt(
                id = request.getString("id") ?: return null,
                title = request.getString("title").orEmpty(),
                message = request.getString("message").orEmpty(),
                positiveText = request.getString("positive").orEmpty()
            )
        }

    override fun request(action: ConfirmationAction, title: String, message: String, positiveText: String) {
        // Keep the saved keys compatible with confirmations created before the boundary refactor.
        val request = Bundle().apply {
            putString("id", UUID.randomUUID().toString())
            putString("title", title)
            putString("message", message)
            putString("positive", positiveText)
            when (action) {
                is ConfirmationAction.DeleteCurrent -> {
                    putString("operation", "DELETE_CURRENT")
                    putString("mode", action.mode.name)
                    putBundle("media", action.target.toBundle())
                }
                ConfirmationAction.DeleteAll -> {
                    putString("operation", "DELETE_ALL")
                    putLong("revision", LyricsStorage.currentRevision())
                    putString("process", LyricsStorage.processGeneration)
                }
                is ConfirmationAction.Search -> {
                    putString("operation", "SEARCH")
                    putBundle("media", action.target.toBundle())
                }
                is ConfirmationAction.DeleteEditor -> {
                    putString("operation", "DELETE_EDITOR")
                    putString("editor", action.sessionId)
                }
            }
        }
        interactions.write(KEY) { clear(); putAll(request) }
    }

    override fun cancel(id: String) {
        if (interactions.read(KEY)?.getString("id") == id) interactions.remove(KEY)
    }

    override fun confirm(id: String) {
        val request = interactions.read(KEY)?.takeIf { it.getString("id") == id } ?: return
        // Consume before dispatch: repeated clicks and old dialogs cannot repeat an operation.
        interactions.remove(KEY)
        when (val action = request.action()) {
            is ConfirmationAction.DeleteCurrent -> deleteCurrent(action.target.toMedia(), action.mode.toStorageMode())
            ConfirmationAction.DeleteAll -> deleteAll(request)
            is ConfirmationAction.Search -> search(action.target.toMedia())
            is ConfirmationAction.DeleteEditor -> if (action.sessionId == editorSessionId()) deleteEditor()
            null -> Unit
        }
    }

    private fun Bundle.action(): ConfirmationAction? = when (getString("operation")) {
        "DELETE_CURRENT" -> {
            val target = target()
            val mode = LyricsDeleteMode.entries.find { it.name == getString("mode") }
            if (target != null && mode != null) ConfirmationAction.DeleteCurrent(target, mode) else null
        }
        "DELETE_ALL" -> if (containsKey("revision") && getString("process") != null) ConfirmationAction.DeleteAll else null
        "SEARCH" -> target()?.let(ConfirmationAction::Search)
        "DELETE_EDITOR" -> getString("editor")?.let(ConfirmationAction::DeleteEditor)
        else -> null
    }

    private fun Bundle.target(): LyricsOperationTarget? = getBundle("media")?.let {
        LyricsOperationTarget(
            it.getString("source").orEmpty(),
            SongIdentity(it.getString("title").orEmpty(), it.getString("artist").orEmpty(),
                it.getString("album").orEmpty(), it.getLong("duration"))
        )
    }

    private fun LyricsOperationTarget.toBundle() = Bundle().apply {
        putString("source", sourcePackage)
        putString("title", song.title)
        putString("artist", song.artist)
        putString("album", song.album)
        putLong("duration", song.durationMs)
    }

    private fun LyricsOperationTarget.toMedia() = CurrentMediaInfo(
        sourcePackage, song.title, song.artist, song.album, song.durationMs, false, 0L
    )

    private fun LyricsDeleteMode.toStorageMode() = when (this) {
        LyricsDeleteMode.PLAIN -> LyricsStorage.DeleteMode.PLAIN
        LyricsDeleteMode.WORD_BY_WORD -> LyricsStorage.DeleteMode.WORD_BY_WORD
        LyricsDeleteMode.ALL -> LyricsStorage.DeleteMode.ALL
    }

    private companion object { const val KEY = "confirmation" }
}

internal fun CurrentMediaInfo.toOperationTarget() = LyricsOperationTarget(
    sourcePackage, SongIdentity(title, artist, album, durationMs)
)
