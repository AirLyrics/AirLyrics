package com.andsi.airlyrics.app.viewmodel

import android.os.Bundle
import com.andsi.airlyrics.app.interaction.MainOperationConfirmations
import com.andsi.airlyrics.app.interaction.toOperationTarget
import com.andsi.airlyrics.ui.model.ConfirmationAction
import com.andsi.airlyrics.ui.model.LyricsDeleteMode
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainViewModelConfirmationTest : MainViewModelTestBase() {
    private fun request(process: String = LyricsStorage.processGeneration, revision: Long = LyricsStorage.currentRevision()) = Bundle().apply {
        putString("id", "request")
        putString("operation", "DELETE_ALL")
        putString("process", process)
        putLong("revision", revision)
    }

    @Test fun staleProcessAndChangedCollectionRequireANewConfirmation() = runTest(mainDispatcherRule.dispatcher) {
        for (request in listOf(request(process = "old-process"), request(revision = -1))) {
            val operations = FakeLyricsOperations()
            val model = viewModel(lyrics = operations)
            val effects = recordEffects(model)
            model.deleteAllSavedLyricsIfUnchanged(request)
            advanceUntilIdle()
            assertEquals(0, operations.deleteAllRequests)
            assertNotEquals("request", model.interactions.read("confirmation")!!.getString("id"))
            assertEquals(listOf(MainUiEffect.RestoreOperationConfirmation), effects)
        }
    }

    @Test fun matchingConfirmationDeletesAndExplicitSongTargetSurvivesMediaChange() = runTest(mainDispatcherRule.dispatcher) {
        val operations = FakeLyricsOperations().apply { currentMedia = media(songB) }
        val model = viewModel(lyrics = operations)
        model.deleteAllSavedLyricsIfUnchanged(request())
        model.deleteLyricsForTarget(media(songA), LyricsStorage.DeleteMode.PLAIN)
        model.searchOnlineLyrics(media(songA))
        advanceUntilIdle()
        assertEquals(1, operations.deleteAllRequests)
        assertEquals(media(songA), operations.currentDeleteRequests.single().first)
        assertEquals(media(songA), operations.onlineSearchRequests.single())
    }
    @Test fun restoredConfirmationRequiresFreshApproval_thenExecutesOnlyOnce() = runTest(mainDispatcherRule.dispatcher) {
        val operations = FakeLyricsOperations()
        val model = viewModel(lyrics = operations)
        val controller = confirmations(model)
        controller.request(ConfirmationAction.DeleteAll, "Delete", "All lyrics", "Delete")
        val oldId = requireNotNull(controller.pending).id
        model.interactions.write("confirmation") { putString("process", "previous-process") }
        controller.confirm(oldId)
        controller.confirm(oldId)
        advanceUntilIdle()
        assertEquals(0, operations.deleteAllRequests)
        val renewed = requireNotNull(controller.pending)
        assertNotEquals(oldId, renewed.id)
        assertEquals("All lyrics", renewed.message)
        controller.confirm(oldId)
        assertEquals(renewed, controller.pending)
        controller.confirm(renewed.id)
        controller.confirm(renewed.id)
        advanceUntilIdle()
        assertEquals(1, operations.deleteAllRequests)
        assertNull(controller.pending)
    }

    @Test fun confirmationDispatchUsesDisplayedTargetAfterPlaybackChanges() = runTest(mainDispatcherRule.dispatcher) {
        val operations = FakeLyricsOperations().apply { currentMedia = media(songA) }
        val model = viewModel(lyrics = operations)
        val controller = confirmations(model)
        controller.request(ConfirmationAction.DeleteCurrent(media(songA).toOperationTarget(), LyricsDeleteMode.PLAIN),
            "Delete", "Song A", "Delete")
        operations.currentMedia = media(songB)
        controller.confirm(requireNotNull(controller.pending).id)
        advanceUntilIdle()
        assertEquals(songA, operations.currentDeleteRequests.single().first.toOperationTarget().song)
        controller.request(ConfirmationAction.Search(media(songA).toOperationTarget()), "Search", "Song A", "Search")
        controller.confirm(requireNotNull(controller.pending).id)
        advanceUntilIdle()
        assertEquals(songA, operations.onlineSearchRequests.single().toOperationTarget().song)
    }

    private fun confirmations(model: MainViewModel) = MainOperationConfirmations(
        interactions = model.interactions,
        editorSessionId = { null },
        deleteAll = model::deleteAllSavedLyricsIfUnchanged,
        deleteCurrent = model::deleteLyricsForTarget,
        search = { model.searchOnlineLyrics(it) },
        deleteEditor = { error("No editor in this test") }
    )

}
