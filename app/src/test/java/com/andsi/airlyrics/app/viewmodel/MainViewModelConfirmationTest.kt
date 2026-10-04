package com.andsi.airlyrics.app.viewmodel

import android.os.Bundle
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
}
