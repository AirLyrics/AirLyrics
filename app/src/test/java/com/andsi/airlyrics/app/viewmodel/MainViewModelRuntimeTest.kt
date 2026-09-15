package com.andsi.airlyrics.app.viewmodel

import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.controller.FloatingFontImportOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainViewModelRuntimeTest : MainViewModelTestBase() {
    @Test
    fun floatingFontImport_mapsOutcomesAndRefreshesStructureOnlyOnSuccess() =
        runTest(mainDispatcherRule.dispatcher) {
            val cases = listOf(
                FloatingFontCase(
                    FloatingFontImportOutcome.Success("Custom.ttf"),
                    MainUiEffect.FloatingFontImported("Custom.ttf"),
                    incrementsRevision = true
                ),
                FloatingFontCase(
                    FloatingFontImportOutcome.UnsupportedFormat,
                    MainUiEffect.ShowMessage(R.string.ui_font_format_unsupported, error = true)
                ),
                FloatingFontCase(
                    FloatingFontImportOutcome.TooLarge,
                    MainUiEffect.ShowMessage(R.string.ui_font_file_too_large, error = true)
                ),
                FloatingFontCase(
                    FloatingFontImportOutcome.InvalidFont,
                    MainUiEffect.ShowMessage(R.string.ui_invalid_font_file, error = true)
                ),
                FloatingFontCase(
                    FloatingFontImportOutcome.ReadFailed,
                    MainUiEffect.ShowMessage(R.string.ui_font_import_failed, error = true)
                )
            )

            cases.forEach { case ->
                val fontImporter = FakeFontImportOperation(case.outcome)
                val viewModel = viewModel(fontImporter = fontImporter)
                val effects = recordEffects(viewModel)

                viewModel.importFloatingFont(null)
                viewModel.importFloatingFont(uriA)
                advanceUntilIdle()

                assertEquals(listOf(uriA), fontImporter.requests)
                assertEquals(
                    listOf(
                        MainUiEffect.ShowMessage(R.string.ui_importing_font),
                        case.expected
                    ),
                    effects
                )
                assertEquals(
                    if (case.incrementsRevision) 1L else 0L,
                    viewModel.uiState.value.floatingStructureRevision
                )
            }
        }

    @Test
    fun foregroundRefreshAndDelayedRefresh_areDeterministicAndCancelable() =
        runTest(mainDispatcherRule.dispatcher) {
            val foreground = FakeForegroundSnapshotReader()
            val viewModel = viewModel(foreground = foreground)
            foreground.snapshot = ForegroundUiSnapshot(
                permissions = PermissionUiSnapshot(overlayGranted = true),
                floating = FloatingUiSnapshot(
                    visible = true,
                    desiredVisible = true,
                    locked = true,
                    clickThrough = true
                ),
                lyricsRevision = 4L
            )

            assertTrue(viewModel.refreshForegroundState())
            assertFalse(viewModel.refreshForegroundState())
            assertEquals(2, foreground.readCount)
            assertTrue(viewModel.locked)
            assertTrue(viewModel.clickThrough)

            foreground.readCount = 0
            foreground.snapshot = ForegroundUiSnapshot(lyricsRevision = 5L)
            viewModel.scheduleMediaRefresh(delayMs = 1_000L)
            advanceTimeBy(999.milliseconds)
            runCurrent()
            assertEquals(0, foreground.readCount)
            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(1, foreground.readCount)

            viewModel.scheduleMediaRefresh(delayMs = 1_000L)
            viewModel.cancelMediaRefresh()
            advanceUntilIdle()
            assertEquals(1, foreground.readCount)
        }

    @Test
    fun floatingAndLyricsNotifications_updateOnlyChangedState() {
        val viewModel = viewModel()

        viewModel.updateFloatingState(
            visible = true,
            desiredVisible = true,
            overlayGranted = true,
            locked = true,
            clickThrough = true
        )
        assertTrue(viewModel.quickFloatingVisible)
        assertTrue(viewModel.quickFloatingDesiredVisible)
        assertTrue(viewModel.overlayPermissionGranted)
        assertTrue(viewModel.locked)
        assertTrue(viewModel.clickThrough)

        viewModel.notifyLyricsChanged(10L)
        viewModel.notifyLyricsChanged(10L)
        viewModel.notifyFloatingStructureChanged()

        assertEquals(10L, viewModel.uiState.value.foreground.lyricsRevision)
        assertEquals(1L, viewModel.uiState.value.lyricsChangeSequence)
        assertEquals(1L, viewModel.uiState.value.floatingStructureRevision)
    }

}
