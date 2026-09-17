package com.andsi.airlyrics.app.viewmodel

import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.controller.CurrentLyricsDeleteOutcome
import com.andsi.airlyrics.app.controller.OnlineLyricsSearchOutcome
import com.andsi.airlyrics.lyrics.LyricsLookupErrorType
import com.andsi.airlyrics.lyrics.LyricsLookupException
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainViewModelLyricsActionsTest : MainViewModelTestBase() {
    @Test
    fun onlineSearch_mapsNoMediaSuccessNotFoundAndFailures() =
        runTest(mainDispatcherRule.dispatcher) {
            val lookupError = LyricsLookupException(
                providerId = "provider",
                providerName = "Provider",
                errorType = LyricsLookupErrorType.NetworkError,
                detailMessage = "offline"
            )
            val cases = listOf(
                OnlineSearchCase(
                    OnlineLyricsSearchOutcome.Saved,
                    MainUiEffect.ShowMessage(R.string.ui_online_lyrics_saved)
                ),
                OnlineSearchCase(
                    OnlineLyricsSearchOutcome.NotFound,
                    MainUiEffect.ShowMessage(R.string.ui_lyrics_not_found)
                ),
                OnlineSearchCase(
                    OnlineLyricsSearchOutcome.LookupFailed(lookupError),
                    MainUiEffect.ShowLyricsLookupError(lookupError)
                ),
                OnlineSearchCase(
                    OnlineLyricsSearchOutcome.Failed,
                    MainUiEffect.ShowMessage(
                        R.string.ui_online_lyrics_search_failed,
                        error = true
                    )
                )
            )

            val missingMediaViewModel = viewModel(lyrics = FakeLyricsOperations())
            val missingMediaEffects = recordEffects(missingMediaViewModel)
            missingMediaViewModel.searchOnlineLyricsForCurrentMedia()
            assertEquals(
                listOf(MainUiEffect.ShowMessage(R.string.ui_no_active_media_found)),
                missingMediaEffects
            )

            cases.forEach { case ->
                val lyrics = FakeLyricsOperations().apply {
                    currentMedia = media(songA)
                    onlineSearchOutcome = case.outcome
                }
                val viewModel = viewModel(lyrics = lyrics)
                val effects = recordEffects(viewModel)

                viewModel.searchOnlineLyricsForCurrentMedia()
                advanceUntilIdle()

                assertEquals(
                    listOf(
                        MainUiEffect.ShowMessage(R.string.ui_searching_online_again),
                        case.expected
                    ),
                    effects
                )
                assertEquals(listOf(media(songA)), lyrics.onlineSearchRequests)
                assertEquals(1, lyrics.onlineSearchTokens.size)
                assertFalse(lyrics.onlineSearchTokens.single().isCancellationRequested)
            }
        }

    @Test
    fun onlineSearch_latestRequestCancelsInFlightLookupAndOnlyReportsLatestOutcome() =
        runTest(mainDispatcherRule.dispatcher) {
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val firstReturned = CountDownLatch(1)
            val secondReturned = CountDownLatch(1)
            val lyrics = FakeLyricsOperations().apply {
                currentMedia = media(songA)
            }
            lyrics.onlineSearchHandler = { requestedMedia, _ ->
                if (requestedMedia.title == songA.title) {
                    firstStarted.countDown()
                    try {
                        check(releaseFirst.await(5, TimeUnit.SECONDS))
                        OnlineLyricsSearchOutcome.NotFound
                    } finally {
                        firstReturned.countDown()
                    }
                } else {
                    secondReturned.countDown()
                    OnlineLyricsSearchOutcome.Saved
                }
            }
            val expectedEffects = listOf(
                MainUiEffect.ShowMessage(R.string.ui_searching_online_again),
                MainUiEffect.ShowMessage(R.string.ui_searching_online_again),
                MainUiEffect.ShowMessage(R.string.ui_online_lyrics_saved)
            )
            val executor = Executors.newFixedThreadPool(2)
            val ioDispatcher = executor.asCoroutineDispatcher()
            lateinit var effects: MutableList<MainUiEffect>

            try {
                val viewModel = viewModel(lyrics = lyrics, ioDispatcher = ioDispatcher)
                effects = recordEffects(viewModel)

                viewModel.searchOnlineLyricsForCurrentMedia()
                runCurrent()
                assertTrue(firstStarted.await(5, TimeUnit.SECONDS))

                lyrics.currentMedia = media(songB)
                viewModel.searchOnlineLyricsForCurrentMedia()
                runCurrent()
                assertTrue(secondReturned.await(5, TimeUnit.SECONDS))

                val latestOutcomeDeadline =
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (effects.size < expectedEffects.size &&
                    System.nanoTime() < latestOutcomeDeadline
                ) {
                    runCurrent()
                    Thread.yield()
                }

                assertEquals(1L, firstReturned.count)
                assertEquals(expectedEffects, effects)
                assertEquals(listOf(media(songA), media(songB)), lyrics.onlineSearchRequests)
                assertEquals(2, lyrics.onlineSearchTokens.size)
                assertTrue(lyrics.onlineSearchTokens.first().isCancellationRequested)
                assertFalse(lyrics.onlineSearchTokens.last().isCancellationRequested)

                releaseFirst.countDown()
                assertTrue(firstReturned.await(5, TimeUnit.SECONDS))
            } finally {
                releaseFirst.countDown()
                ioDispatcher.close()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }

            advanceUntilIdle()
            assertEquals(expectedEffects, effects)
        }

    @Test
    fun currentLyricsDeletion_mapsEveryModeAndEmptyOutcome() =
        runTest(mainDispatcherRule.dispatcher) {
            val cases = listOf(
                DeleteCase(true, LyricsStorage.DeleteMode.PLAIN, R.string.ui_plain_lrc_removed_for_this_song),
                DeleteCase(false, LyricsStorage.DeleteMode.PLAIN, R.string.ui_no_plain_lrc_to_remove_for_this_song),
                DeleteCase(true, LyricsStorage.DeleteMode.WORD_BY_WORD, R.string.ui_word_by_word_lyrics_removed),
                DeleteCase(false, LyricsStorage.DeleteMode.WORD_BY_WORD, R.string.ui_no_word_by_word_lyrics_to_remove),
                DeleteCase(true, LyricsStorage.DeleteMode.ALL, R.string.ui_all_local_lyrics_removed),
                DeleteCase(false, LyricsStorage.DeleteMode.ALL, R.string.ui_no_local_lyrics_to_remove)
            )

            cases.forEach { case ->
                val lyrics = FakeLyricsOperations().apply {
                    currentMedia = media(songA)
                    currentDeleteResult = CurrentLyricsDeleteOutcome(case.deleted, case.mode)
                }
                val viewModel = viewModel(lyrics = lyrics)
                val effects = recordEffects(viewModel)

                viewModel.deleteLyricsForCurrentMedia(case.mode)
                advanceUntilIdle()

                assertEquals(listOf(MainUiEffect.ShowMessage(case.messageRes)), effects)
                assertEquals(listOf(media(songA) to case.mode), lyrics.currentDeleteRequests)
            }
        }

    @Test
    fun savedLyricsDeletion_returnsEachOutcomeAndReportsItsMessage() =
        runTest(mainDispatcherRule.dispatcher) {
            val lyrics = FakeLyricsOperations().apply {
                savedDeleteResults += LyricsStorage.DeleteLocalLyricsItemResult.Deleted(songA)
                savedDeleteResults += LyricsStorage.DeleteLocalLyricsItemResult.NotFound
                savedDeleteResults += LyricsStorage.DeleteLocalLyricsItemResult.Failed
            }
            val viewModel = viewModel(lyrics = lyrics)
            val effects = recordEffects(viewModel)
            val items = listOf("one", "two", "three").map(::localItem)

            val deletions = items.map(viewModel::deleteSavedLyricsItem)
            advanceUntilIdle()

            assertEquals(listOf(true, false, false), deletions.awaitAll())
            assertEquals(items, lyrics.savedDeleteRequests)
            assertEquals(
                listOf(
                    MainUiEffect.ShowMessage(R.string.ui_all_saved_lyrics_deleted),
                    MainUiEffect.ShowMessage(R.string.ui_lyrics_not_found),
                    MainUiEffect.ShowMessage(R.string.ui_delete_saved_lyrics_failed, error = true)
                ),
                effects
            )
        }

    @Test
    fun deleteAllSavedLyrics_mapsEveryStorageOutcome() =
        runTest(mainDispatcherRule.dispatcher) {
            val cases = listOf(
                LyricsStorage.DeleteAllSavedLyricsResult.DELETED to
                    MainUiEffect.ShowMessage(R.string.ui_all_saved_lyrics_deleted),
                LyricsStorage.DeleteAllSavedLyricsResult.NOTHING_TO_DELETE to
                    MainUiEffect.ShowMessage(R.string.ui_no_saved_lyrics_to_delete),
                LyricsStorage.DeleteAllSavedLyricsResult.FAILED to
                    MainUiEffect.ShowMessage(R.string.ui_delete_all_saved_lyrics_failed, error = true)
            )

            cases.forEach { (outcome, expected) ->
                val lyrics = FakeLyricsOperations().apply { deleteAllResult = outcome }
                val viewModel = viewModel(lyrics = lyrics)
                val effects = recordEffects(viewModel)

                viewModel.deleteAllSavedLyrics()
                advanceUntilIdle()

                assertEquals(listOf(expected), effects)
                assertEquals(1, lyrics.deleteAllRequests)
            }
        }

    @Test
    fun lyricsDirectory_reportsFailureOrUpdatesRevisionAfterSuccess() =
        runTest(mainDispatcherRule.dispatcher) {
            val lyrics = FakeLyricsOperations().apply {
                lyricsDirectory = "/lyrics"
                setDirectoryResult = false
            }
            val viewModel = viewModel(lyrics = lyrics)
            val effects = recordEffects(viewModel)

            assertEquals("/lyrics", viewModel.lyricsDirectoryPath())
            viewModel.setLyricsDirectory(uriA)
            advanceUntilIdle()
            assertEquals(0L, viewModel.uiState.value.lyricsDirectoryRevision)
            assertEquals(
                listOf(MainUiEffect.ShowMessage(R.string.ui_lyrics_folder_write_failed, error = true)),
                effects
            )

            effects.clear()
            lyrics.setDirectoryResult = true
            viewModel.setLyricsDirectory(uriB)
            advanceUntilIdle()

            assertEquals(1L, viewModel.uiState.value.lyricsDirectoryRevision)
            assertEquals(
                listOf(MainUiEffect.ShowMessage(R.string.ui_lyrics_save_folder_set)),
                effects
            )
            assertEquals(listOf(uriA, uriB), lyrics.directoryRequests)
        }

}
