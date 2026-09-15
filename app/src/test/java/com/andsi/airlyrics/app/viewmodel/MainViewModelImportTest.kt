package com.andsi.airlyrics.app.viewmodel

import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.controller.LyricsDocumentValidation
import com.andsi.airlyrics.app.controller.LyricsImportAvailability
import com.andsi.airlyrics.app.controller.LyricsImportOutcome
import com.andsi.airlyrics.app.state.LyricsImportType
import com.andsi.airlyrics.app.state.PendingLyricsImport
import com.andsi.airlyrics.app.state.PendingLyricsOverwrite
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainViewModelImportTest : MainViewModelTestBase() {
    @Test
    fun requestLyricsImport_requiresMediaAndPublishesStorageAvailability() =
        runTest(mainDispatcherRule.dispatcher) {
            val lyrics = FakeLyricsOperations()
            val viewModel = viewModel(lyrics = lyrics)
            val effects = recordEffects(viewModel)

            viewModel.requestLyricsImport()
            assertEquals(
                listOf(MainUiEffect.ShowMessage(R.string.ui_select_song_before_importing)),
                effects
            )

            effects.clear()
            lyrics.currentMedia = media(songA)
            lyrics.availability = LyricsImportAvailability(
                plainImportEnabled = false,
                wordByWordImportEnabled = true
            )
            viewModel.requestLyricsImport()
            advanceUntilIdle()

            assertEquals(
                listOf(
                    MainUiEffect.ShowLyricsImportChoices(
                        target = songA,
                        plainImportEnabled = false,
                        wordByWordImportEnabled = true
                    )
                ),
                effects
            )
            assertEquals(listOf(songA), lyrics.availabilityRequests)
        }

    @Test
    fun cancelledPicker_consumesRequestWithoutStartingImport() =
        runTest(mainDispatcherRule.dispatcher) {
            val lyrics = FakeLyricsOperations()
            val viewModel = viewModel(lyrics = lyrics)
            val effects = recordEffects(viewModel)

            viewModel.beginLyricsImport(songA, LyricsImportType.WORD_BY_WORD)
            viewModel.handleLyricsFileResult(null)

            assertEquals(listOf(MainUiEffect.SelectLyricsFile), effects)
            assertNull(viewModel.uiState.value.pendingLyricsImport)
            assertTrue(lyrics.importRequests.isEmpty())
        }

    @Test
    fun pickedFile_importsTheCapturedTargetInsteadOfNewCurrentMedia() =
        runTest(mainDispatcherRule.dispatcher) {
            val lyrics = FakeLyricsOperations().apply {
                currentMedia = media(songB)
                importOutcomes += LyricsImportOutcome.Finished(
                    LyricsStorage.ImportLyricsResult.Saved,
                    importAsWordByWord = false
                )
            }
            val viewModel = viewModel(lyrics = lyrics)
            val effects = recordEffects(viewModel)

            viewModel.setPendingLyricsImport(PendingLyricsImport(songA, LyricsImportType.PLAIN))
            viewModel.handleLyricsFileResult(uriA)
            advanceUntilIdle()

            assertEquals(
                listOf(ImportRequest(uriA, songA, overwrite = false, wordByWord = false)),
                lyrics.importRequests
            )
            assertEquals(
                listOf(
                    MainUiEffect.ShowMessage(R.string.ui_importing_lyrics),
                    MainUiEffect.ShowMessage(R.string.ui_plain_lrc_import_success)
                ),
                effects
            )
        }

    @Test
    fun pickedFile_validationFailuresUseTypeSpecificMessages() =
        runTest(mainDispatcherRule.dispatcher) {
            val cases = listOf(
                Triple(
                    LyricsImportType.PLAIN,
                    LyricsDocumentValidation.UnsupportedFormat,
                    MainUiEffect.ShowMessage(
                        R.string.ui_please_choose_a_plain_lrc_lyrics_file,
                        error = true
                    )
                ),
                Triple(
                    LyricsImportType.WORD_BY_WORD,
                    LyricsDocumentValidation.UnsupportedFormat,
                    MainUiEffect.ShowMessage(
                        R.string.ui_please_choose_a_word_by_word_lrc_file,
                        error = true
                    )
                ),
                Triple(
                    LyricsImportType.PLAIN,
                    LyricsDocumentValidation.TooLarge,
                    MainUiEffect.ShowMessage(R.string.ui_lrc_file_too_large, error = true)
                )
            )

            cases.forEach { (type, validation, expectedEffect) ->
                val lyrics = FakeLyricsOperations().apply { documentValidation = validation }
                val viewModel = viewModel(lyrics = lyrics)
                val effects = recordEffects(viewModel)
                viewModel.setPendingLyricsImport(PendingLyricsImport(songA, type))

                viewModel.handleLyricsFileResult(uriA)
                advanceUntilIdle()

                assertEquals(listOf(expectedEffect), effects)
                assertTrue(lyrics.importRequests.isEmpty())
            }
        }

    @Test
    fun importResults_areMappedToTypedPresentationEffects() =
        runTest(mainDispatcherRule.dispatcher) {
            val cases = listOf(
                ImportResultCase(
                    LyricsStorage.ImportLyricsResult.TooLarge,
                    expected = MainUiEffect.ShowMessage(R.string.ui_lrc_file_too_large, error = true)
                ),
                ImportResultCase(
                    LyricsStorage.ImportLyricsResult.InvalidFormat(listOf(2, 7)),
                    expected = MainUiEffect.ShowImportFormatError(listOf(2, 7), wordByWord = false)
                ),
                ImportResultCase(
                    LyricsStorage.ImportLyricsResult.PlainLyricsAlreadyExists,
                    expected = MainUiEffect.ShowMessage(
                        R.string.ui_word_by_word_blocked_by_plain_lrc,
                        error = true
                    )
                ),
                ImportResultCase(
                    LyricsStorage.ImportLyricsResult.WordByWordLyricsAlreadyExists,
                    expected = MainUiEffect.ShowMessage(
                        R.string.ui_plain_lrc_blocked_by_word_by_word,
                        error = true
                    )
                ),
                ImportResultCase(
                    LyricsStorage.ImportLyricsResult.ReadFailed,
                    expected = MainUiEffect.ShowMessage(
                        R.string.ui_cannot_read_this_lyric_file,
                        error = true
                    )
                ),
                ImportResultCase(
                    LyricsStorage.ImportLyricsResult.ReadFailed,
                    wordByWord = true,
                    expected = MainUiEffect.ShowMessage(
                        R.string.ui_cannot_read_word_by_word_lyrics_file,
                        error = true
                    )
                ),
                ImportResultCase(
                    LyricsStorage.ImportLyricsResult.SaveFailed,
                    expected = MainUiEffect.ShowMessage(
                        R.string.ui_lrc_import_save_failed,
                        error = true
                    )
                ),
                ImportResultCase(
                    LyricsStorage.ImportLyricsResult.SnapshotFailed,
                    expected = MainUiEffect.ShowMessage(
                        R.string.ui_lrc_import_save_failed,
                        error = true
                    )
                ),
                ImportResultCase(
                    rollbackFailure(),
                    expected = MainUiEffect.ShowMessage(
                        R.string.ui_lrc_import_save_failed,
                        error = true
                    )
                )
            )

            cases.forEach { case ->
                val lyrics = FakeLyricsOperations().apply {
                    importOutcomes += LyricsImportOutcome.Finished(case.result, case.wordByWord)
                }
                val viewModel = viewModel(lyrics = lyrics)
                val effects = recordEffects(viewModel)
                val type = if (case.wordByWord) {
                    LyricsImportType.WORD_BY_WORD
                } else {
                    LyricsImportType.PLAIN
                }
                viewModel.setPendingLyricsImport(PendingLyricsImport(songA, type))

                viewModel.handleLyricsFileResult(uriA)
                advanceUntilIdle()

                assertEquals(
                    listOf(
                        MainUiEffect.ShowMessage(R.string.ui_importing_lyrics),
                        case.expected
                    ),
                    effects
                )
            }
        }

    @Test
    fun overwriteConfirmation_requiresTheCurrentRequestAndImportsItOnce() =
        runTest(mainDispatcherRule.dispatcher) {
            val expected = PendingLyricsOverwrite(uriA, songA, LyricsImportType.WORD_BY_WORD)
            val lyrics = FakeLyricsOperations().apply {
                importOutcomes += LyricsImportOutcome.ConfirmationRequired(expected)
                importOutcomes += LyricsImportOutcome.Finished(
                    LyricsStorage.ImportLyricsResult.Saved,
                    importAsWordByWord = true
                )
            }
            val viewModel = viewModel(lyrics = lyrics)
            val effects = recordEffects(viewModel)
            viewModel.setPendingLyricsImport(
                PendingLyricsImport(songA, LyricsImportType.WORD_BY_WORD)
            )

            viewModel.handleLyricsFileResult(uriA)
            advanceUntilIdle()
            assertEquals(expected, viewModel.uiState.value.pendingLyricsOverwrite)

            viewModel.confirmLyricsOverwrite(expected.copy(uri = uriB))
            assertEquals(1, lyrics.importRequests.size)

            viewModel.confirmLyricsOverwrite(expected)
            advanceUntilIdle()
            viewModel.confirmLyricsOverwrite(expected)

            assertNull(viewModel.uiState.value.pendingLyricsOverwrite)
            assertEquals(
                listOf(
                    ImportRequest(uriA, songA, overwrite = false, wordByWord = true),
                    ImportRequest(uriA, songA, overwrite = true, wordByWord = true)
                ),
                lyrics.importRequests
            )
            assertEquals(
                listOf(
                    MainUiEffect.ShowMessage(R.string.ui_importing_lyrics),
                    MainUiEffect.ShowMessage(R.string.ui_importing_lyrics),
                    MainUiEffect.ShowMessage(R.string.ui_word_by_word_lyrics_import_success)
                ),
                effects
            )
        }

}
