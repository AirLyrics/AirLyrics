package com.andsi.airlyrics.app.controller

import com.andsi.airlyrics.testutil.seedPlainLyrics
import com.andsi.airlyrics.testutil.resetLyricsStorage
import android.content.Context
import android.media.session.MediaController
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.app.contracts.MediaControllerProvider
import com.andsi.airlyrics.app.state.LyricsImportType
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.LyricsChange
import com.andsi.airlyrics.lyrics.LyricsChangedPublisher
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.media.toSongIdentity
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class LyricsControllerOutcomeTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetLyricsStorage(context)
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        ShadowLog.clear()
        resetLyricsStorage(context)
    }

    @Test
    fun savedImport_returnsFinishedAndPublishesTarget() {
        val cases = listOf(
            ImportCase(
                wordByWord = false,
                target = target("Plain success"),
                source = "[00:10.00]plain success"
            ),
            ImportCase(
                wordByWord = true,
                target = target("Word-by-word success"),
                source = "[00:10.00]<00:10.00>word-by-word success"
            )
        )

        cases.forEachIndexed { index, case ->
            val publisher = RecordingLyricsChangedPublisher()
            val outcome = controller(publisher = publisher).importLyricsForTarget(
                uri = writeImportFile("success-$index.lrc", case.source),
                target = case.target,
                overwrite = false,
                importAsWordByWord = case.wordByWord
            )

            assertEquals(
                LyricsImportOutcome.Finished(
                    LyricsStorage.ImportLyricsResult.Saved,
                    case.wordByWord
                ),
                outcome
            )
            assertEquals(listOf(LyricsChange.updated(case.target)), publisher.changes)
            if (case.wordByWord) {
                assertTrue(
                    LyricsStorage.hasWordByWordLyrics(
                        context,
                        case.target.title,
                        case.target.artist,
                        case.target.durationMs
                    )
                )
            } else {
                assertTrue(
                    LyricsStorage.hasPlainLyrics(
                        context,
                        case.target.title,
                        case.target.artist,
                        case.target.durationMs
                    )
                )
            }
        }
    }

    @Test
    fun existingLyrics_returnsTypedOverwriteRequestWithoutReadingNewFile() {
        val target = target("Existing lyrics")
        assertTrue(
            seedPlainLyrics(
                context = context,
                title = target.title,
                artist = target.artist,
                album = target.album,
                duration = target.durationMs,
                plainLrc = "[00:01.00]existing"
            )
        )
        val uri = Uri.parse("content://lyrics/new-file.lrc")

        val outcome = controller().importLyricsForTarget(
            uri = uri,
            target = target,
            overwrite = false,
            importAsWordByWord = false
        )

        assertTrue(outcome is LyricsImportOutcome.ConfirmationRequired)
        val request = (outcome as LyricsImportOutcome.ConfirmationRequired).request
        assertEquals(uri, request.uri)
        assertEquals(target, request.target)
        assertEquals(LyricsImportType.PLAIN, request.type)
    }

    @Test
    fun failedImports_returnGatewayResultWithoutPublishingChange() {
        val rollbackFailed = rollbackFailedResult()
        val results = listOf(
            LyricsStorage.ImportLyricsResult.TooLarge,
            LyricsStorage.ImportLyricsResult.InvalidFormat(listOf(2, 7)),
            LyricsStorage.ImportLyricsResult.PlainLyricsAlreadyExists,
            LyricsStorage.ImportLyricsResult.WordByWordLyricsAlreadyExists,
            LyricsStorage.ImportLyricsResult.ReadFailed,
            LyricsStorage.ImportLyricsResult.SaveFailed,
            LyricsStorage.ImportLyricsResult.SnapshotFailed,
            rollbackFailed
        )

        results.forEachIndexed { index, expected ->
            val publisher = RecordingLyricsChangedPublisher()
            val outcome = controller(
                publisher = publisher,
                importGateway = FixedResultLyricsImportGateway(expected)
            ).importLyricsForTarget(
                uri = Uri.parse("content://lyrics/failure-$index.lrc"),
                target = target("Failure $index"),
                overwrite = true
            )

            assertEquals(LyricsImportOutcome.Finished(expected, false), outcome)
            assertTrue(publisher.changes.isEmpty())
        }
        assertRollbackDiagnosticLog()
    }

    @Test
    fun deleteCurrentLyrics_returnsOutcomeAndPublishesTargetedDeletion() {
        val media = media("Delete current")
        assertTrue(
            seedPlainLyrics(
                context = context,
                title = media.title,
                artist = media.artist,
                duration = media.durationMs,
                album = media.album,
                plainLrc = "[00:01.00]delete me"
            )
        )
        val publisher = RecordingLyricsChangedPublisher()

        val outcome = controller(publisher = publisher).deleteLyricsForCurrentMedia(
            media,
            LyricsStorage.DeleteMode.PLAIN
        )

        assertEquals(
            CurrentLyricsDeleteOutcome(deleted = true, mode = LyricsStorage.DeleteMode.PLAIN),
            outcome
        )
        assertFalse(
            LyricsStorage.hasPlainLyrics(context, media.title, media.artist, media.durationMs)
        )
        assertEquals(listOf(LyricsChange.deleted(media.toSongIdentity())), publisher.changes)
    }

    @Test
    fun deleteAllLyrics_returnsStorageOutcomeAndPublishesGlobalDeletion() {
        val media = media("Delete all")
        assertTrue(
            seedPlainLyrics(
                context = context,
                title = media.title,
                artist = media.artist,
                duration = media.durationMs,
                album = media.album,
                plainLrc = "[00:01.00]delete everything"
            )
        )
        val publisher = RecordingLyricsChangedPublisher()

        val outcome = controller(publisher = publisher).deleteAllSavedLyrics()

        assertEquals(LyricsStorage.DeleteAllSavedLyricsResult.DELETED, outcome)
        assertEquals(listOf(LyricsChange.deleted()), publisher.changes)
    }

    private fun controller(
        publisher: LyricsChangedPublisher = RecordingLyricsChangedPublisher(),
        importGateway: LyricsImportGateway = StorageLyricsImportGateway(),
        mediaControllerProvider: MediaControllerProvider = EmptyMediaControllerProvider
    ): LyricsController {
        return LyricsController(
            context = context,
            mediaControllerProvider = mediaControllerProvider,
            lyricsChangedPublisher = publisher,
            lyricsImportGateway = importGateway,
        )
    }

    private fun assertRollbackDiagnosticLog() {
        val messages = ShadowLog.getLogsForTag("LyricsController")
            .filter { it.type == Log.ERROR }
            .map { it.msg }
        assertTrue(messages.any { it.contains("INDEX_WRITE") })
        assertTrue(messages.any { it.contains("IO_OPERATION_RETURNED_FALSE") })
        assertTrue(messages.any { it.contains("RESTORE_INDEX") })
        assertTrue(messages.any { it.contains("RESTORE_WORD_BY_WORD_FILE") })
    }

    private fun rollbackFailedResult(): LyricsStorage.ImportLyricsResult.RollbackFailed {
        return LyricsStorage.ImportLyricsResult.RollbackFailed(
            originalFailureStep = LyricsStorage.WordByWordImportFailureStep.INDEX_WRITE,
            originalFailureCause =
                LyricsStorage.WordByWordImportFailureCause.IO_OPERATION_RETURNED_FALSE,
            failedRollbackSteps = listOf(
                LyricsStorage.WordByWordRollbackFailureStep.RESTORE_INDEX,
                LyricsStorage.WordByWordRollbackFailureStep.RESTORE_WORD_BY_WORD_FILE
            )
        )
    }

    private fun target(name: String): SongIdentity {
        return SongIdentity(
            title = name,
            artist = "Outcome artist",
            album = "Outcome album",
            durationMs = 180_000L
        )
    }

    private fun media(name: String): CurrentMediaInfo {
        return CurrentMediaInfo(
            sourcePackage = "com.example.player",
            title = name,
            artist = "Outcome artist",
            album = "Outcome album",
            durationMs = 180_000L,
            isPlaying = true,
            positionMs = 1_000L
        )
    }

    private fun writeImportFile(name: String, text: String): Uri {
        return Uri.fromFile(File(context.cacheDir, name).apply { writeText(text) })
    }

    private data class ImportCase(
        val wordByWord: Boolean,
        val target: SongIdentity,
        val source: String
    )

    private class FixedResultLyricsImportGateway(
        private val result: LyricsStorage.ImportLyricsResult
    ) : LyricsImportGateway {
        override fun importPlainLyrics(
            context: Context,
            uri: Uri,
            target: SongIdentity,
            overwrite: Boolean
        ): LyricsStorage.ImportLyricsResult = result

        override fun importWordByWordLyrics(
            context: Context,
            uri: Uri,
            target: SongIdentity,
            overwrite: Boolean
        ): LyricsStorage.ImportLyricsResult = result
    }

    private class RecordingLyricsChangedPublisher : LyricsChangedPublisher {
        val changes = mutableListOf<LyricsChange>()

        override fun publish(change: LyricsChange) {
            changes += change
        }
    }

    private data object EmptyMediaControllerProvider : MediaControllerProvider {
        override fun getActiveControllers(): List<MediaController> = emptyList()
    }

}
