package com.andsi.airlyrics.app.viewmodel

import android.content.Context
import android.media.session.MediaController
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.app.contracts.MediaControllerProvider
import com.andsi.airlyrics.app.controller.FloatingFontImporter
import com.andsi.airlyrics.app.controller.LyricsController
import com.andsi.airlyrics.app.state.LyricsImportType
import com.andsi.airlyrics.app.state.PendingLyricsImport
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.LyricsChange
import com.andsi.airlyrics.lyrics.LyricsChangedPublisher
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class MainViewModelTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        LyricsStorage.deleteAllSavedLyrics(context)
    }

    @After
    fun tearDown() {
        LyricsStorage.deleteAllSavedLyrics(context)
    }

    @Test
    fun navigationAndSearch_arePersistedInSavedStateHandle() {
        val handle = SavedStateHandle()
        val viewModel = viewModel(handle)

        viewModel.selectPage(Page.SETTINGS)
        viewModel.openSettingsSubPage(SettingsSubPage.SAVED_LYRICS)
        viewModel.setSavedLyricsSearchOpen(true)
        viewModel.updateSavedLyricsSearchQuery("artist")

        val restored = viewModel(handle).uiState.value
        assertEquals(Page.SETTINGS, restored.currentPage)
        assertEquals(SettingsSubPage.SAVED_LYRICS, restored.settingsSubPage)
        assertTrue(restored.savedLyricsSearchOpen)
        assertEquals("artist", restored.savedLyricsSearchQuery)
    }

    @Test
    fun closingSearch_clearsQueryAndPersistsTheClosedState() {
        val handle = SavedStateHandle()
        val viewModel = viewModel(handle)
        viewModel.setSavedLyricsSearchOpen(true)
        viewModel.updateSavedLyricsSearchQuery("query")

        viewModel.setSavedLyricsSearchOpen(false)

        val state = viewModel(handle).uiState.value
        assertFalse(state.savedLyricsSearchOpen)
        assertEquals("", state.savedLyricsSearchQuery)
    }

    @Test
    fun pendingImport_isConsumedOnlyOnceAndRemovedFromSavedState() {
        val handle = SavedStateHandle()
        val request = PendingLyricsImport(
            target = SongIdentity("Song", "Artist", durationMs = 1L),
            type = LyricsImportType.PLAIN
        )
        val viewModel = viewModel(handle)
        viewModel.setPendingLyricsImport(request)

        assertEquals(request, viewModel.consumePendingLyricsImport())
        assertNull(viewModel.consumePendingLyricsImport())
        assertNull(viewModel(handle).uiState.value.pendingLyricsImport)
    }

    @Test
    fun platformRequests_areEmittedAsOneOffEffects() = runBlocking {
        val viewModel = viewModel(SavedStateHandle())

        viewModel.requestOverlayPermission()

        assertEquals(MainUiEffect.RequestOverlayPermission, viewModel.uiEffects.first())
    }

    @Test
    fun savedLyricsDeletion_usesViewModelOwnedIdsAndUpdatesRetainedState() {
        repeat(2) { index ->
            assertTrue(
                LyricsStorage.savePlainLyrics(
                    context = context,
                    title = "Saved song ${index + 1}",
                    artist = "Saved artist ${index + 1}",
                    duration = 180_000L + index,
                    plainLrc = "[00:01.00]saved line"
                )
            )
        }
        val items = LyricsStorage.listAllLyrics(context)
        val viewModel = viewModel(SavedStateHandle())
        viewModel.refreshForegroundState()
        val revisionBeforeDelete = viewModel.uiState.value.foreground.lyricsRevision

        val firstRequestId = viewModel.deleteSavedLyricsItem(items[0])
        val secondRequestId = viewModel.deleteSavedLyricsItem(items[1])

        assertNotEquals(firstRequestId, secondRequestId)
        assertTrue(secondRequestId > firstRequestId)
        awaitCondition {
            LyricsStorage.listAllLyrics(context).isEmpty() &&
                viewModel.uiState.value.foreground.lyricsRevision > revisionBeforeDelete
        }
    }

    private fun viewModel(handle: SavedStateHandle): MainViewModel {
        return MainViewModel(
            savedStateHandle = handle,
            lyricsController = LyricsController(
                context = context,
                mediaControllerProvider = EmptyMediaControllerProvider,
                lyricsChangedPublisher = NoOpLyricsChangedPublisher
            ),
            foregroundStateReader = MainForegroundStateReader(context),
            floatingFontImporter = FloatingFontImporter(context)
        )
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()
        assertTrue("Timed out waiting for ViewModel work", condition())
    }

    private data object EmptyMediaControllerProvider : MediaControllerProvider {
        override fun getActiveControllers(): List<MediaController> = emptyList()
    }

    private data object NoOpLyricsChangedPublisher : LyricsChangedPublisher {
        override fun publish(change: LyricsChange) = Unit
    }
}
