package com.andsi.airlyrics.app.viewmodel

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.andsi.airlyrics.app.controller.CurrentLyricsDeleteOutcome
import com.andsi.airlyrics.app.controller.FloatingFontImportOperation
import com.andsi.airlyrics.app.controller.FloatingFontImportOutcome
import com.andsi.airlyrics.app.controller.LyricsDocumentValidation
import com.andsi.airlyrics.app.controller.LyricsImportAvailability
import com.andsi.airlyrics.app.controller.LyricsImportOutcome
import com.andsi.airlyrics.app.controller.LyricsOperations
import com.andsi.airlyrics.app.controller.OnlineLyricsSearchOutcome
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule

@OptIn(ExperimentalCoroutinesApi::class)
abstract class MainViewModelTestBase {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    internal fun viewModel(
        handle: SavedStateHandle = SavedStateHandle(),
        lyrics: LyricsOperations = FakeLyricsOperations(),
        foreground: ForegroundSnapshotReader = FakeForegroundSnapshotReader(),
        fontImporter: FloatingFontImportOperation =
            FakeFontImportOperation(FloatingFontImportOutcome.ReadFailed)
    ): MainViewModel {
        return MainViewModel(
            savedStateHandle = handle,
            lyricsController = lyrics,
            foregroundStateReader = foreground,
            floatingFontImporter = fontImporter,
            ioDispatcher = mainDispatcherRule.dispatcher
        )
    }

    internal fun TestScope.recordEffects(viewModel: MainViewModel): MutableList<MainUiEffect> {
        val effects = mutableListOf<MainUiEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiEffects.collect(effects::add)
        }
        return effects
    }

    internal class FakeLyricsOperations : LyricsOperations {
        var currentMedia: CurrentMediaInfo? = null
        var documentValidation: LyricsDocumentValidation = LyricsDocumentValidation.Valid
        var availability = LyricsImportAvailability(
            plainImportEnabled = true,
            wordByWordImportEnabled = true
        )
        var onlineSearchOutcome: OnlineLyricsSearchOutcome = OnlineLyricsSearchOutcome.NotFound
        var currentDeleteResult = CurrentLyricsDeleteOutcome(false, LyricsStorage.DeleteMode.ALL)
        var deleteAllResult = LyricsStorage.DeleteAllSavedLyricsResult.NOTHING_TO_DELETE
        var lyricsDirectory = ""
        var setDirectoryResult = true
        val importOutcomes = mutableListOf<LyricsImportOutcome>()
        val savedDeleteResults = mutableListOf<LyricsStorage.DeleteLocalLyricsItemResult>()
        val availabilityRequests = mutableListOf<SongIdentity>()
        val importRequests = mutableListOf<ImportRequest>()
        val onlineSearchRequests = mutableListOf<CurrentMediaInfo>()
        val currentDeleteRequests = mutableListOf<Pair<CurrentMediaInfo, LyricsStorage.DeleteMode>>()
        val savedDeleteRequests = mutableListOf<LyricsStorage.LocalLyricsItem>()
        val directoryRequests = mutableListOf<Uri>()
        var deleteAllRequests = 0

        override fun validatePickedDocument(uri: Uri): LyricsDocumentValidation = documentValidation

        override fun importAvailability(target: SongIdentity): LyricsImportAvailability {
            availabilityRequests += target
            return availability
        }

        override fun importLyricsForTarget(
            uri: Uri,
            target: SongIdentity,
            overwrite: Boolean,
            importAsWordByWord: Boolean
        ): LyricsImportOutcome {
            importRequests += ImportRequest(uri, target, overwrite, importAsWordByWord)
            return if (importOutcomes.isEmpty()) {
                LyricsImportOutcome.Finished(
                    LyricsStorage.ImportLyricsResult.Saved,
                    importAsWordByWord
                )
            } else {
                importOutcomes.removeAt(0)
            }
        }

        override fun deleteLyricsForCurrentMedia(
            media: CurrentMediaInfo,
            mode: LyricsStorage.DeleteMode
        ): CurrentLyricsDeleteOutcome {
            currentDeleteRequests += media to mode
            return currentDeleteResult
        }

        override fun deleteSavedLyricsItem(
            item: LyricsStorage.LocalLyricsItem
        ): LyricsStorage.DeleteLocalLyricsItemResult {
            savedDeleteRequests += item
            return savedDeleteResults.removeAt(0)
        }

        override fun deleteAllSavedLyrics(): LyricsStorage.DeleteAllSavedLyricsResult {
            deleteAllRequests += 1
            return deleteAllResult
        }

        override fun searchOnlineLyricsForCurrentMedia(
            media: CurrentMediaInfo
        ): OnlineLyricsSearchOutcome {
            onlineSearchRequests += media
            return onlineSearchOutcome
        }

        override fun getCurrentMediaInfo(): CurrentMediaInfo? = currentMedia

        override fun lyricsDirectoryPath(): String = lyricsDirectory

        override fun setLyricsDirectory(uri: Uri): Boolean {
            directoryRequests += uri
            return setDirectoryResult
        }
    }

    internal class FakeForegroundSnapshotReader : ForegroundSnapshotReader {
        var snapshot = ForegroundUiSnapshot()
        var readCount = 0

        override fun read(): ForegroundUiSnapshot {
            readCount += 1
            return snapshot
        }
    }

    internal class FakeFontImportOperation(
        var outcome: FloatingFontImportOutcome
    ) : FloatingFontImportOperation {
        val requests = mutableListOf<Uri>()

        override fun import(uri: Uri): FloatingFontImportOutcome {
            requests += uri
            return outcome
        }
    }

    internal data class ImportRequest(
        val uri: Uri,
        val target: SongIdentity,
        val overwrite: Boolean,
        val wordByWord: Boolean
    )

    internal data class ImportResultCase(
        val result: LyricsStorage.ImportLyricsResult,
        val wordByWord: Boolean = false,
        val expected: MainUiEffect
    )

    internal data class OnlineSearchCase(
        val outcome: OnlineLyricsSearchOutcome,
        val expected: MainUiEffect
    )

    internal data class DeleteCase(
        val deleted: Boolean,
        val mode: LyricsStorage.DeleteMode,
        val messageRes: Int
    )

    internal data class FloatingFontCase(
        val outcome: FloatingFontImportOutcome,
        val expected: MainUiEffect,
        val incrementsRevision: Boolean = false
    )

    internal fun media(song: SongIdentity): CurrentMediaInfo {
        return CurrentMediaInfo(
            sourcePackage = "player.app",
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = song.durationMs,
            isPlaying = true,
            positionMs = 1_000L
        )
    }

    internal fun localItem(name: String): LyricsStorage.LocalLyricsItem {
        return LyricsStorage.LocalLyricsItem(
            name = "$name.lrc",
            modifiedTimeMillis = 1L,
            sizeBytes = 10L,
            title = name,
            indexKey = name
        )
    }

    internal fun rollbackFailure(): LyricsStorage.ImportLyricsResult.RollbackFailed {
        return LyricsStorage.ImportLyricsResult.RollbackFailed(
            originalFailureStep = LyricsStorage.WordByWordImportFailureStep.INDEX_WRITE,
            originalFailureCause =
                LyricsStorage.WordByWordImportFailureCause.IO_OPERATION_RETURNED_FALSE,
            failedRollbackSteps = listOf(
                LyricsStorage.WordByWordRollbackFailureStep.RESTORE_INDEX
            )
        )
    }

    internal val songA = SongIdentity("Song A", "Artist A", "Album A", 180_000L)
    internal val songB = SongIdentity("Song B", "Artist B", "Album B", 240_000L)
    internal val uriA: Uri = Uri.parse("content://lyrics/a.lrc")
    internal val uriB: Uri = Uri.parse("content://lyrics/b.lrc")
}
