package com.andsi.airlyrics.app.interaction

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.ui.model.ConfirmationAction
import com.andsi.airlyrics.ui.model.LyricsDeleteMode
import com.andsi.airlyrics.ui.model.LyricsOperationTarget
import com.andsi.airlyrics.ui.state.MainInteractionState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainOperationConfirmationsTest {
    private val handle = SavedStateHandle()
    private val interactions = MainInteractionState(handle)
    private val target = LyricsOperationTarget("player", SongIdentity("Song A", "Artist", "Album", 123000))
    private val deletions = mutableListOf<Pair<CurrentMediaInfo, LyricsStorage.DeleteMode>>()
    private val searches = mutableListOf<CurrentMediaInfo>()
    private val deleteAllRequests = mutableListOf<Bundle>()
    private var editorId: String? = "editor-one"
    private var editorDeletes = 0

    private fun confirmations(state: MainInteractionState = interactions) = MainOperationConfirmations(
        interactions = state,
        editorSessionId = { editorId },
        deleteAll = {
            assertNull(state.read("confirmation"))
            deleteAllRequests += it
        },
        deleteCurrent = { media, mode ->
            assertNull(state.read("confirmation"))
            deletions += media to mode
        },
        search = { searches += it },
        deleteEditor = { editorDeletes++ }
    )

    @Test
    fun restoredRequestPreservesTargetAndMode_andIsConsumedBeforeDispatch() {
        val original = confirmations()
        original.request(ConfirmationAction.DeleteCurrent(target, LyricsDeleteMode.WORD_BY_WORD), "Delete", "Song A", "Remove")
        val prompt = requireNotNull(original.pending)
        val saved = Bundle(requireNotNull(handle.get<Bundle>("airlyrics.interactions.v1")))
        val restoredState = MainInteractionState(SavedStateHandle(mapOf("airlyrics.interactions.v1" to saved)))
        val restored = confirmations(restoredState)

        assertEquals(prompt, restored.pending)
        restored.confirm(prompt.id)
        restored.confirm(prompt.id)

        val (media, mode) = deletions.single()
        assertEquals(target, media.toOperationTarget())
        assertEquals(LyricsStorage.DeleteMode.WORD_BY_WORD, mode)
        assertNull(restored.pending)
    }

    @Test
    fun staleDialogCannotCancelOrExecuteANewerRequest() {
        val controller = confirmations()
        controller.request(ConfirmationAction.DeleteAll, "Delete", "All", "Delete")
        val oldId = requireNotNull(controller.pending).id
        controller.request(ConfirmationAction.Search(target), "Search", "Song A", "Search")
        val current = requireNotNull(controller.pending)

        controller.cancel(oldId)
        controller.confirm(oldId)
        assertEquals(current, controller.pending)
        assertTrue(deleteAllRequests.isEmpty())
        assertTrue(searches.isEmpty())

        controller.confirm(current.id)
        assertEquals(target, searches.single().toOperationTarget())
        assertNull(controller.pending)
    }

    @Test
    fun cancellationPreventsLaterConfirmation() {
        val controller = confirmations()
        controller.request(ConfirmationAction.Search(target), "Search", "Song A", "Search")
        val id = requireNotNull(controller.pending).id
        controller.cancel(id)
        controller.confirm(id)
        assertNull(controller.pending)
        assertTrue(searches.isEmpty())
    }

    @Test
    fun deleteAllCapturesStorageGuardWithoutExposingItInThePrompt() {
        val controller = confirmations()
        controller.request(ConfirmationAction.DeleteAll, "Delete", "All", "Delete")
        val revision = LyricsStorage.currentRevision()
        val process = LyricsStorage.processGeneration
        controller.confirm(requireNotNull(controller.pending).id)
        assertEquals(revision, deleteAllRequests.single().getLong("revision"))
        assertEquals(process, deleteAllRequests.single().getString("process"))
    }

    @Test
    fun editorConfirmationWaitsForRestoration_andCannotDeleteAnotherSession() {
        val controller = confirmations()
        controller.request(ConfirmationAction.DeleteEditor("editor-one"), "Delete", "Editor", "Delete")
        val prompt = requireNotNull(controller.pending)
        editorId = null
        assertNull(controller.pending)
        assertNotNull(interactions.read("confirmation"))
        editorId = "editor-one"
        assertEquals(prompt, controller.pending)
        editorId = "editor-two"
        controller.confirm(prompt.id)
        assertEquals(0, editorDeletes)
        assertNull(interactions.read("confirmation"))

        controller.request(ConfirmationAction.DeleteEditor("editor-two"), "Delete", "Editor", "Delete")
        val id = requireNotNull(controller.pending).id
        controller.confirm(id)
        controller.confirm(id)
        assertEquals(1, editorDeletes)
    }

    @Test
    fun preRefactorSavedRequestRemainsReadable() {
        interactions.write("confirmation") {
            putString("id", "legacy")
            putString("operation", "DELETE_CURRENT")
            putString("title", "Delete")
            putString("message", "Song A")
            putString("positive", "Remove")
            putString("mode", "PLAIN")
            putLong("revision", 42)
            putString("process", "old-process")
            putBundle("media", Bundle().apply {
                putString("source", "player")
                putString("title", "Song A")
                putString("artist", "Artist")
                putString("album", "Album")
                putLong("duration", 123000)
            })
        }
        val controller = confirmations()
        assertEquals("legacy", controller.pending?.id)
        controller.confirm("legacy")
        assertEquals(target, deletions.single().first.toOperationTarget())
        assertEquals(LyricsStorage.DeleteMode.PLAIN, deletions.single().second)
    }

    @Test
    fun incompleteTargetDoesNotFallBackToTheCurrentlyPlayingSong() {
        interactions.write("confirmation") {
            putString("id", "missing-target")
            putString("operation", "SEARCH")
        }
        val controller = confirmations()
        assertNull(controller.pending)
        controller.confirm("missing-target")
        assertTrue(searches.isEmpty())
    }
}
