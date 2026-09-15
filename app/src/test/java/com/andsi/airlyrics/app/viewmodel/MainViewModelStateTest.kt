package com.andsi.airlyrics.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.andsi.airlyrics.app.state.LyricsImportType
import com.andsi.airlyrics.app.state.PendingLyricsImport
import com.andsi.airlyrics.app.state.PendingLyricsOverwrite
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainViewModelStateTest : MainViewModelTestBase() {
    @Test
    fun navigationAndSearch_arePersistedAndBackNavigationFollowsPageHierarchy() {
        val handle = SavedStateHandle()
        val viewModel = viewModel(handle = handle)

        assertFalse(viewModel.navigateBack())
        viewModel.selectPage(Page.SETTINGS)
        viewModel.openSettingsSubPage(SettingsSubPage.SAVED_LYRICS)
        viewModel.setSavedLyricsSearchOpen(true)
        viewModel.updateSavedLyricsSearchQuery("artist")

        assertTrue(viewModel.navigateBack())
        assertEquals(SettingsSubPage.LYRICS, viewModel.uiState.value.settingsSubPage)

        val restored = viewModel(handle = handle).uiState.value
        assertEquals(Page.SETTINGS, restored.currentPage)
        assertEquals(SettingsSubPage.LYRICS, restored.settingsSubPage)
        assertTrue(restored.savedLyricsSearchOpen)
        assertEquals("artist", restored.savedLyricsSearchQuery)

        assertTrue(viewModel.navigateBack())
        assertEquals(SettingsSubPage.HOME, viewModel.uiState.value.settingsSubPage)
        assertFalse(viewModel.navigateBack())
    }

    @Test
    fun closingSearch_clearsQueryAndPersistsTheClosedState() {
        val handle = SavedStateHandle()
        val viewModel = viewModel(handle = handle)
        viewModel.setSavedLyricsSearchOpen(true)
        viewModel.updateSavedLyricsSearchQuery("query")

        viewModel.setSavedLyricsSearchOpen(false)

        val state = viewModel(handle = handle).uiState.value
        assertFalse(state.savedLyricsSearchOpen)
        assertEquals("", state.savedLyricsSearchQuery)
    }

    @Test
    fun pendingRequests_areConsumedOnlyByTheMatchingActionAndRemovedFromSavedState() {
        val handle = SavedStateHandle()
        val importRequest = PendingLyricsImport(songA, LyricsImportType.PLAIN)
        val overwriteRequest = PendingLyricsOverwrite(uriA, songA, LyricsImportType.PLAIN)
        val otherOverwriteRequest = PendingLyricsOverwrite(uriB, songA, LyricsImportType.PLAIN)
        val viewModel = viewModel(handle = handle)

        viewModel.setPendingLyricsImport(importRequest)
        viewModel.requestLyricsOverwrite(overwriteRequest)

        assertNull(viewModel.consumePendingLyricsOverwrite(otherOverwriteRequest))
        assertFalse(viewModel.clearPendingLyricsOverwrite(otherOverwriteRequest))
        assertEquals(importRequest, viewModel.consumePendingLyricsImport())
        assertNull(viewModel.consumePendingLyricsImport())
        assertTrue(viewModel.clearPendingLyricsOverwrite(overwriteRequest))
        assertNull(viewModel(handle = handle).uiState.value.pendingLyricsImport)
        assertNull(viewModel(handle = handle).uiState.value.pendingLyricsOverwrite)
    }

    @Test
    fun platformRequests_areEmittedOnceInRequestOrder() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel()
        val effects = recordEffects(viewModel)

        viewModel.requestOverlayPermission()
        viewModel.requestNotificationPermission()
        viewModel.openNotificationListenerSettings()
        viewModel.openUsageAccessSettings()
        viewModel.selectLyricsDirectory()
        viewModel.selectLyricsFile()
        viewModel.selectFloatingFontFile()

        assertEquals(
            listOf(
                MainUiEffect.RequestOverlayPermission,
                MainUiEffect.RequestNotificationPermission,
                MainUiEffect.OpenNotificationListenerSettings,
                MainUiEffect.OpenUsageAccessSettings,
                MainUiEffect.SelectLyricsDirectory,
                MainUiEffect.SelectLyricsFile,
                MainUiEffect.SelectFloatingFontFile
            ),
            effects
        )
    }

}
