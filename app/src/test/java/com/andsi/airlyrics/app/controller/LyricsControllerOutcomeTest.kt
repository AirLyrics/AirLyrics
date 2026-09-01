package com.andsi.airlyrics.app.controller

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.app.contracts.MediaControllerProvider
import com.andsi.airlyrics.app.state.LyricsImportType
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.LyricsChange
import com.andsi.airlyrics.lyrics.LyricsChangedPublisher
import com.andsi.airlyrics.lyrics.LyricsLookupErrorType
import com.andsi.airlyrics.lyrics.LyricsLookupException
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.storage.FALLBACK_LYRICS_DIR
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.lyrics.storage.PREFS_NAME
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.media.toSongIdentity
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class LyricsControllerOutcomeTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetStorage()
        MediaSourceStore.saveSelectedPackage(context, null)
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        MediaSourceStore.saveSelectedPackage(context, null)
        ShadowLog.clear()
        resetStorage()
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
            LyricsStorage.savePlainLyrics(
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
    fun onlineLookup_mapsSavedNotFoundAndFailures() {
        val media = media("Manual lookup")
        val lookupError = LyricsLookupException(
            providerId = "provider",
            providerName = "Provider",
            errorType = LyricsLookupErrorType.NetworkError,
            detailMessage = "offline"
        )
        val cases = listOf(
            OnlineCase(
                result = Result.success(
                    LyricsProviderResult(
                        plainProviderId = "provider",
                        plainProviderName = "Provider",
                        plainLrc = "[00:01.00]online"
                    )
                ),
                expected = OnlineLyricsSearchOutcome.Saved,
                publishes = true
            ),
            OnlineCase(
                result = Result.success(null),
                expected = OnlineLyricsSearchOutcome.NotFound
            ),
            OnlineCase(
                result = Result.failure(lookupError),
                expected = OnlineLyricsSearchOutcome.LookupFailed(lookupError)
            ),
            OnlineCase(
                result = Result.failure(IllegalStateException("broken")),
                expected = OnlineLyricsSearchOutcome.Failed
            )
        )

        cases.forEach { case ->
            val publisher = RecordingLyricsChangedPublisher()
            val gateway = RecordingOnlineLyricsLookupGateway(case.result)
            val outcome = controller(
                publisher = publisher,
                onlineGateway = gateway
            ).searchOnlineLyricsForCurrentMedia(media)

            assertEquals(case.expected, outcome)
            assertEquals(listOf(context), gateway.contextRequests)
            assertEquals(listOf(media), gateway.mediaRequests)
            val expectedChanges = if (case.publishes) {
                listOf(LyricsChange.updated(media.toSongIdentity()))
            } else {
                emptyList()
            }
            assertEquals(expectedChanges, publisher.changes)
        }
    }

    @Test
    fun deleteCurrentLyrics_returnsOutcomeAndPublishesTargetedDeletion() {
        val media = media("Delete current")
        assertTrue(
            LyricsStorage.savePlainLyrics(
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
            LyricsStorage.savePlainLyrics(
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

    @Test
    fun deleteSavedLyricsItem_deletesExactNonCurrentItemWithoutGlobalChange() {
        val song = target("Delete saved item")
        savePlainLyrics(song)
        val item = LyricsStorage.listAllLyrics(context).single()
        val publisher = RecordingLyricsChangedPublisher()

        val outcome = controller(publisher = publisher).deleteSavedLyricsItem(item)

        assertTrue(outcome is LyricsStorage.DeleteLocalLyricsItemResult.Deleted)
        assertTrue(LyricsStorage.listAllLyrics(context).isEmpty())
        assertTrue(publisher.changes.isEmpty())
    }

    @Suppress("UsePropertyAccessSyntax")
    @Test
    fun deleteSavedLyricsItem_publishesDeletionWhenItMatchesCurrentMedia() {
        val song = target("Delete current saved item")
        savePlainLyrics(song)
        val item = LyricsStorage.listAllLyrics(context).single()
        val session = MediaSession(context, "delete-current-saved-item")
        val mediaController = MediaController(context, session.sessionToken)
        shadowOf(mediaController).apply {
            setPackageName(CURRENT_MEDIA_PACKAGE)
            setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, song.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, song.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, song.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, song.durationMs)
                    .build()
            )
        }
        MediaSourceStore.saveSelectedPackage(context, CURRENT_MEDIA_PACKAGE)
        val publisher = RecordingLyricsChangedPublisher()

        try {
            val outcome = controller(
                publisher = publisher,
                mediaControllerProvider = FixedMediaControllerProvider(listOf(mediaController))
            ).deleteSavedLyricsItem(item)

            assertTrue(outcome is LyricsStorage.DeleteLocalLyricsItemResult.Deleted)
            assertEquals(listOf(LyricsChange.deleted(song)), publisher.changes)
        } finally {
            session.release()
        }
    }

    @Test
    fun deleteSavedLyricsItem_staleKeyDoesNotFallBackToFilename() {
        val song = target("Stale saved item")
        savePlainLyrics(song)
        val staleItem = LyricsStorage.listAllLyrics(context)
            .single()
            .copy(indexKey = "missing-key")
        val publisher = RecordingLyricsChangedPublisher()

        val outcome = controller(publisher = publisher).deleteSavedLyricsItem(staleItem)

        assertSame(LyricsStorage.DeleteLocalLyricsItemResult.NotFound, outcome)
        assertEquals(1, LyricsStorage.listAllLyrics(context).size)
        assertTrue(publisher.changes.isEmpty())
    }

    private fun controller(
        publisher: LyricsChangedPublisher = RecordingLyricsChangedPublisher(),
        importGateway: LyricsImportGateway = StorageLyricsImportGateway(),
        onlineGateway: OnlineLyricsLookupGateway =
            RecordingOnlineLyricsLookupGateway(Result.success(null)),
        mediaControllerProvider: MediaControllerProvider = EmptyMediaControllerProvider
    ): LyricsController {
        return LyricsController(
            context = context,
            mediaControllerProvider = mediaControllerProvider,
            lyricsChangedPublisher = publisher,
            lyricsImportGateway = importGateway,
            onlineLyricsLookupGateway = onlineGateway
        )
    }

    private fun savePlainLyrics(song: SongIdentity) {
        assertTrue(
            LyricsStorage.savePlainLyrics(
                context = context,
                title = song.title,
                artist = song.artist,
                album = song.album,
                duration = song.durationMs,
                plainLrc = "[00:01.00]saved"
            )
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

    private fun resetStorage() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        File(base, FALLBACK_LYRICS_DIR).deleteRecursively()
    }

    private data class ImportCase(
        val wordByWord: Boolean,
        val target: SongIdentity,
        val source: String
    )

    private data class OnlineCase(
        val result: Result<LyricsProviderResult?>,
        val expected: OnlineLyricsSearchOutcome,
        val publishes: Boolean = false
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

    private class RecordingOnlineLyricsLookupGateway(
        private val result: Result<LyricsProviderResult?>
    ) : OnlineLyricsLookupGateway {
        val contextRequests = mutableListOf<Context>()
        val mediaRequests = mutableListOf<CurrentMediaInfo>()

        override fun findAndSave(
            context: Context,
            media: CurrentMediaInfo
        ): Result<LyricsProviderResult?> {
            contextRequests += context
            mediaRequests += media
            return result
        }
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

    private class FixedMediaControllerProvider(
        private val controllers: List<MediaController>
    ) : MediaControllerProvider {
        override fun getActiveControllers(): List<MediaController> = controllers
    }

    private companion object {
        const val CURRENT_MEDIA_PACKAGE = "com.example.current"
    }
}
