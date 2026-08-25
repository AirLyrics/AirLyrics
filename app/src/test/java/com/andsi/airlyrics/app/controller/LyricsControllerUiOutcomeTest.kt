package com.andsi.airlyrics.app.controller

import android.app.Dialog
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.app.contracts.MainDialogHost
import com.andsi.airlyrics.app.contracts.MainTaskRunner
import com.andsi.airlyrics.app.contracts.MediaControllerProvider
import com.andsi.airlyrics.app.render.UiInvalidator
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.importer.plainLyricsFormatErrorMessage
import com.andsi.airlyrics.lyrics.LyricsChange
import com.andsi.airlyrics.lyrics.LyricsChangeKind
import com.andsi.airlyrics.lyrics.LyricsChangedPublisher
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.storage.FALLBACK_LYRICS_DIR
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.lyrics.storage.PREFS_NAME
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.media.toSongIdentity
import com.andsi.airlyrics.settings.store.AppSettingsStore
import com.andsi.airlyrics.ui.components.showAirInfoDialog
import com.andsi.airlyrics.ui.refresh.PageRebuildReason
import java.io.File
import java.util.ArrayDeque
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
class LyricsControllerUiOutcomeTest {
    private lateinit var activityController: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var dialogHost: ProductionDialogHost

    @Before
    fun setUp() {
        activityController = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = activityController.get()
        dialogHost = ProductionDialogHost(activity)
        resetStorage()
        resetUiState()
    }

    @After
    fun tearDown() {
        dialogHost.dismissAll()
        MediaSourceStore.saveSelectedPackage(activity, null)
        AppSettingsStore.setToasterMuted(activity, false)
        ShadowToast.reset()
        ShadowLog.clear()
        resetStorage()
        activityController.close()
    }

    @Test
    fun savedImport_showsSuccessAndPublishesWithoutDirectUiRefresh() {
        val cases = listOf(
            SuccessCase(
                wordByWordLyrics = false,
                target = target("Plain success"),
                source = "[00:10.00]plain success",
                expectedToast = activity.getString(R.string.ui_plain_lrc_import_success)
            ),
            SuccessCase(
                wordByWordLyrics = true,
                target = target("Word-by-word success"),
                source = "[00:10.00]<00:10.00>word-by-word success",
                expectedToast = activity.getString(R.string.ui_word_by_word_lyrics_import_success)
            )
        )

        cases.forEachIndexed { index, case ->
            resetUiState()
            val runner = ControlledTaskRunner()
            val invalidator = RecordingInvalidator()
            val reloader = RecordingReloader()
            val publisher = RecordingLyricsChangedPublisher()
            val controller = controller(runner, invalidator, reloader, publisher = publisher)

            controller.importLyricsForTarget(
                uri = writeImportFile("success-$index.lrc", case.source),
                target = case.target,
                overwrite = false,
                importAsWordByWord = case.wordByWordLyrics
            )
            runner.drain()

            val toastTexts = shownToastTexts()
            assertEquals(case.expectedToast, toastTexts.last())
            assertFalse(successErrorToastTexts().any(ShadowToast::showedToast))
            assertTrue(dialogHost.shownDialogs.isEmpty())
            assertEquals(listOf(case.target), publisher.targets)
            assertEquals(listOf(LyricsChangeKind.UPDATED), publisher.changes.map(LyricsChange::kind))
            assertTrue(reloader.commands.isEmpty())
            assertTrue(invalidator.rebuildCalls.isEmpty())
            if (case.wordByWordLyrics) {
                assertTrue(
                    LyricsStorage.hasWordByWordLyrics(
                        activity,
                        case.target.title,
                        case.target.artist,
                        case.target.durationMs
                    )
                )
            } else {
                assertTrue(
                    LyricsStorage.hasPlainLyrics(
                        activity,
                        case.target.title,
                        case.target.artist,
                        case.target.durationMs
                    )
                )
            }
        }
    }

    @Test
    fun failureResults_renderExpectedToastOrDialogWithoutSuccessSideEffects() {
        val rollbackFailed = rollbackFailedResult()
        val cases = listOf(
            FailureCase(
                name = "TooLarge",
                result = LyricsStorage.ImportLyricsResult.TooLarge,
                expectedToastRes = R.string.ui_lrc_file_too_large
            ),
            FailureCase(
                name = "InvalidFormat",
                result = LyricsStorage.ImportLyricsResult.InvalidFormat(listOf(2, 7)),
                expectedDialog = true
            ),
            FailureCase(
                name = "PlainLyricsAlreadyExists",
                result = LyricsStorage.ImportLyricsResult.PlainLyricsAlreadyExists,
                wordByWordLyrics = true,
                expectedToastRes = R.string.ui_word_by_word_blocked_by_plain_lrc
            ),
            FailureCase(
                name = "WordByWordLyricsAlreadyExists",
                result = LyricsStorage.ImportLyricsResult.WordByWordLyricsAlreadyExists,
                expectedToastRes = R.string.ui_plain_lrc_blocked_by_word_by_word
            ),
            FailureCase(
                name = "ReadFailed",
                result = LyricsStorage.ImportLyricsResult.ReadFailed,
                expectedToastRes = R.string.ui_cannot_read_this_lyric_file
            ),
            FailureCase(
                name = "SaveFailed",
                result = LyricsStorage.ImportLyricsResult.SaveFailed,
                expectedToastRes = R.string.ui_lrc_import_save_failed
            ),
            FailureCase(
                name = "SnapshotFailed",
                result = LyricsStorage.ImportLyricsResult.SnapshotFailed,
                expectedToastRes = R.string.ui_lrc_import_save_failed
            ),
            FailureCase(
                name = "RollbackFailed",
                result = rollbackFailed,
                expectedToastRes = R.string.ui_lrc_import_save_failed
            )
        )

        cases.forEachIndexed { index, case ->
            resetUiState()
            val runner = ControlledTaskRunner()
            val invalidator = RecordingInvalidator()
            val reloader = RecordingReloader()
            val publisher = RecordingLyricsChangedPublisher()
            val controller = controller(
                runner = runner,
                invalidator = invalidator,
                reloader = reloader,
                gateway = FixedResultLyricsImportGateway(case.result),
                publisher = publisher
            )

            controller.importLyricsForTarget(
                uri = Uri.parse("content://lyrics-outcome/${case.name}"),
                target = target("Failure $index"),
                overwrite = true,
                importAsWordByWord = case.wordByWordLyrics
            )
            runner.drain()

            assertFailureHasNoSuccessSideEffects(invalidator, reloader)
            assertTrue(publisher.targets.isEmpty())
            if (case.expectedDialog) {
                assertInvalidFormatDialog(listOf(2, 7))
                assertFalse(successErrorToastTexts().any(ShadowToast::showedToast))
            } else {
                assertEquals(
                    activity.getString(requireNotNull(case.expectedToastRes)),
                    shownToastTexts().last()
                )
                assertTrue(dialogHost.shownDialogs.isEmpty())
            }
            if (case.result == rollbackFailed) {
                assertRollbackDiagnosticLog()
            }
        }
    }

    @Test
    fun hiddenToast_suppressesFailureToastWithoutChangingOutcome() {
        val cases = listOf(
            FailureCase("ReadFailed", LyricsStorage.ImportLyricsResult.ReadFailed),
            FailureCase("SaveFailed", LyricsStorage.ImportLyricsResult.SaveFailed),
            FailureCase("SnapshotFailed", LyricsStorage.ImportLyricsResult.SnapshotFailed),
            FailureCase("RollbackFailed", rollbackFailedResult()),
            FailureCase(
                name = "InvalidFormat",
                result = LyricsStorage.ImportLyricsResult.InvalidFormat(listOf(3, 11)),
                expectedDialog = true
            )
        )

        AppSettingsStore.setToasterMuted(activity, true)
        cases.forEachIndexed { index, case ->
            resetUiState(keepToastMuted = true)
            val runner = ControlledTaskRunner()
            val invalidator = RecordingInvalidator()
            val reloader = RecordingReloader()
            val publisher = RecordingLyricsChangedPublisher()
            val controller = controller(
                runner = runner,
                invalidator = invalidator,
                reloader = reloader,
                gateway = FixedResultLyricsImportGateway(case.result),
                publisher = publisher
            )

            controller.importLyricsForTarget(
                uri = Uri.parse("content://lyrics-outcome/hidden/${case.name}"),
                target = target("Hidden failure $index"),
                overwrite = true,
                importAsWordByWord = false
            )
            runner.drain()

            assertTrue(shownToastTexts().isEmpty())
            assertFailureHasNoSuccessSideEffects(invalidator, reloader)
            assertTrue(publisher.targets.isEmpty())
            if (case.expectedDialog) {
                assertInvalidFormatDialog(listOf(3, 11))
            } else {
                assertTrue(dialogHost.shownDialogs.isEmpty())
            }
            if (case.result is LyricsStorage.ImportLyricsResult.RollbackFailed) {
                assertRollbackDiagnosticLog()
            }
        }
    }

    @Test
    fun manualOnlineLookup_publishesSavedLyricsWithoutRequiringFloatingWindow() {
        val runner = ControlledTaskRunner()
        val invalidator = RecordingInvalidator()
        val reloader = RecordingReloader()
        val publisher = RecordingLyricsChangedPublisher()
        val media = media("Manual online lookup")
        val gateway = RecordingOnlineLyricsLookupGateway(
            Result.success(
                LyricsProviderResult(
                    plainProviderId = "netease",
                    plainProviderName = "NetEase",
                    plainLrc = "[00:01.00]online result"
                )
            )
        )
        val controller = controller(
            runner = runner,
            invalidator = invalidator,
            reloader = reloader,
            publisher = publisher,
            onlineGateway = gateway
        )

        controller.searchOnlineLyricsForCurrentMedia(media)
        runner.drain()

        assertEquals(listOf(activity), gateway.contextRequests)
        assertEquals(listOf(media), gateway.mediaRequests)
        assertEquals(listOf(media.toSongIdentity()), publisher.targets)
        assertEquals(listOf(LyricsChangeKind.UPDATED), publisher.changes.map(LyricsChange::kind))
        assertTrue(ShadowToast.showedToast(activity.getString(R.string.ui_online_lyrics_saved)))
        assertTrue(reloader.commands.isEmpty())
        assertTrue(invalidator.rebuildCalls.isEmpty())
    }

    @Test
    fun manualOnlineLookup_notFoundReportsOutcomeWithoutPublishingChange() {
        val runner = ControlledTaskRunner()
        val publisher = RecordingLyricsChangedPublisher()
        val gateway = RecordingOnlineLyricsLookupGateway(Result.success(null))
        val controller = controller(
            runner = runner,
            invalidator = RecordingInvalidator(),
            reloader = RecordingReloader(),
            publisher = publisher,
            onlineGateway = gateway
        )

        controller.searchOnlineLyricsForCurrentMedia(media("No online result"))
        runner.drain()

        assertTrue(ShadowToast.showedToast(activity.getString(R.string.ui_lyrics_not_found)))
        assertTrue(publisher.targets.isEmpty())
    }

    @Test
    fun deleteCurrentLyrics_publishesChangeWithoutRequestingPageRebuild() {
        val runner = ControlledTaskRunner()
        val invalidator = RecordingInvalidator()
        val reloader = RecordingReloader()
        val publisher = RecordingLyricsChangedPublisher()
        val media = media("Delete without page rebuild")
        assertTrue(
            LyricsStorage.savePlainLyrics(
                context = activity,
                title = media.title,
                artist = media.artist,
                duration = media.durationMs,
                album = media.album,
                plainLrc = "[00:01.00]delete me"
            )
        )
        val controller = controller(runner, invalidator, reloader, publisher = publisher)

        controller.deleteLyricsForCurrentMedia(media, LyricsStorage.DeleteMode.PLAIN)
        runner.drain()

        assertFalse(LyricsStorage.hasPlainLyrics(activity, media.title, media.artist, media.durationMs))
        assertEquals(listOf(media.toSongIdentity()), publisher.targets)
        assertEquals(listOf(LyricsChangeKind.DELETED), publisher.changes.map(LyricsChange::kind))
        assertTrue(invalidator.rebuildCalls.isEmpty())
        assertTrue(reloader.commands.isEmpty())
    }

    @Test
    fun deleteAllLyrics_publishesGlobalDeletionWithoutOrdinaryReload() {
        val runner = ControlledTaskRunner()
        val invalidator = RecordingInvalidator()
        val reloader = RecordingReloader()
        val publisher = RecordingLyricsChangedPublisher()
        val media = media("Delete all without online refill")
        assertTrue(
            LyricsStorage.savePlainLyrics(
                context = activity,
                title = media.title,
                artist = media.artist,
                duration = media.durationMs,
                album = media.album,
                plainLrc = "[00:01.00]delete everything"
            )
        )
        val controller = controller(runner, invalidator, reloader, publisher = publisher)

        controller.deleteAllSavedLyrics()
        runner.drain()

        assertFalse(LyricsStorage.hasPlainLyrics(activity, media.title, media.artist, media.durationMs))
        assertEquals(listOf(LyricsChange.deleted()), publisher.changes)
        assertTrue(invalidator.rebuildCalls.isEmpty())
        assertTrue(reloader.commands.isEmpty())
    }

    @Test
    fun deleteSavedLyricsItem_deletesExactItemAndCompletesWithoutGlobalChange() {
        val song = target("Delete saved item")
        assertTrue(
            LyricsStorage.savePlainLyrics(
                context = activity,
                title = song.title,
                artist = song.artist,
                album = song.album,
                duration = song.durationMs,
                plainLrc = "[00:01.00]delete me"
            )
        )
        val item = LyricsStorage.listAllLyrics(activity).single()
        val runner = ControlledTaskRunner()
        val invalidator = RecordingInvalidator()
        val reloader = RecordingReloader()
        val publisher = RecordingLyricsChangedPublisher()
        val completions = mutableListOf<Boolean>()
        val controller = controller(runner, invalidator, reloader, publisher = publisher)

        controller.deleteSavedLyricsItem(item, completions::add)
        runner.drain()

        assertEquals(listOf(true), completions)
        assertTrue(LyricsStorage.listAllLyrics(activity).isEmpty())
        assertTrue(publisher.changes.isEmpty())
        assertTrue(invalidator.rebuildCalls.isEmpty())
        assertTrue(reloader.commands.isEmpty())
    }

    @Suppress("UsePropertyAccessSyntax")
    @Test
    fun deleteSavedLyricsItem_publishesTargetedDeletionOnlyForCurrentItem() {
        val song = target("Delete current saved item")
        assertTrue(
            LyricsStorage.savePlainLyrics(
                context = activity,
                title = song.title,
                artist = song.artist,
                album = song.album,
                duration = song.durationMs,
                plainLrc = "[00:01.00]delete current"
            )
        )
        val item = LyricsStorage.listAllLyrics(activity).single()
        val session = MediaSession(activity, "delete-saved-lyrics-current")
        val mediaController = MediaController(activity, session.sessionToken)
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
        MediaSourceStore.saveSelectedPackage(activity, CURRENT_MEDIA_PACKAGE)
        val runner = ControlledTaskRunner()
        val publisher = RecordingLyricsChangedPublisher()
        val completions = mutableListOf<Boolean>()
        val controller = controller(
            runner = runner,
            invalidator = RecordingInvalidator(),
            reloader = RecordingReloader(),
            publisher = publisher,
            mediaControllerProvider = FixedMediaControllerProvider(listOf(mediaController))
        )

        try {
            controller.deleteSavedLyricsItem(item, completions::add)
            runner.drain()

            assertEquals(listOf(true), completions)
            assertEquals(listOf(LyricsChange.deleted(song)), publisher.changes)
        } finally {
            MediaSourceStore.saveSelectedPackage(activity, null)
            session.release()
        }
    }

    @Test
    fun deleteSavedLyricsItem_staleKeyFailsWithoutFilenameFallback() {
        val song = target("Stale saved item")
        assertTrue(
            LyricsStorage.savePlainLyrics(
                context = activity,
                title = song.title,
                artist = song.artist,
                album = song.album,
                duration = song.durationMs,
                plainLrc = "[00:01.00]keep me"
            )
        )
        val staleItem = LyricsStorage.listAllLyrics(activity).single().copy(indexKey = "missing-key")
        val runner = ControlledTaskRunner()
        val publisher = RecordingLyricsChangedPublisher()
        val completions = mutableListOf<Boolean>()
        val controller = controller(
            runner = runner,
            invalidator = RecordingInvalidator(),
            reloader = RecordingReloader(),
            publisher = publisher
        )

        controller.deleteSavedLyricsItem(staleItem, completions::add)
        runner.drain()

        assertEquals(listOf(false), completions)
        assertEquals(1, LyricsStorage.listAllLyrics(activity).size)
        assertTrue(publisher.changes.isEmpty())
        assertTrue(ShadowToast.showedToast(activity.getString(R.string.ui_lyrics_not_found)))
    }

    private fun controller(
        runner: ControlledTaskRunner,
        invalidator: RecordingInvalidator,
        reloader: RecordingReloader,
        gateway: LyricsImportGateway? = null,
        publisher: LyricsChangedPublisher = RecordingLyricsChangedPublisher(),
        onlineGateway: OnlineLyricsLookupGateway = RecordingOnlineLyricsLookupGateway(Result.success(null)),
        mediaControllerProvider: MediaControllerProvider = EmptyMediaControllerProvider()
    ): LyricsController {
        val common = LyricsControllerDependencies(
            runner = runner,
            invalidator = invalidator,
            reloader = reloader
        )
        return if (gateway == null) {
            LyricsController(
                context = activity,
                taskRunner = common.runner,
                dialogHost = dialogHost,
                mediaControllerProvider = mediaControllerProvider,
                overwriteConfirmationRequester = unexpectedOverwriteRequester(),
                lyricsChangedPublisher = publisher,
                onlineLyricsLookupGateway = onlineGateway
            )
        } else {
            LyricsController(
                context = activity,
                taskRunner = common.runner,
                dialogHost = dialogHost,
                mediaControllerProvider = mediaControllerProvider,
                overwriteConfirmationRequester = unexpectedOverwriteRequester(),
                lyricsImportGateway = gateway,
                lyricsChangedPublisher = publisher,
                onlineLyricsLookupGateway = onlineGateway
            )
        }
    }

    private fun assertFailureHasNoSuccessSideEffects(
        invalidator: RecordingInvalidator,
        reloader: RecordingReloader
    ) {
        assertFalse(ShadowToast.showedToast(activity.getString(R.string.ui_plain_lrc_import_success)))
        assertFalse(ShadowToast.showedToast(activity.getString(R.string.ui_word_by_word_lyrics_import_success)))
        assertTrue(reloader.commands.isEmpty())
        assertTrue(invalidator.rebuildCalls.isEmpty())
    }

    private fun assertInvalidFormatDialog(invalidLineNumbers: List<Int>) {
        assertEquals(1, dialogHost.shownDialogs.size)
        val dialog = dialogHost.shownDialogs.single()
        assertTrue(dialog.isShowing)
        val texts = dialog.window?.decorView?.descendantTexts().orEmpty()
        val expectedTitle = activity.getString(R.string.ui_invalid_format)
        assertTrue("Missing dialog title in $texts", texts.contains(expectedTitle))
        val expectedMessage = activity.plainLyricsFormatErrorMessage(invalidLineNumbers)
        val message = texts.singleOrNull { it == expectedMessage }
        assertNotNull("Missing production format explanation in $texts", message)
        invalidLineNumbers.forEach { line ->
            assertTrue("Missing invalid line $line in $message", message!!.contains(line.toString()))
        }
    }

    private fun assertRollbackDiagnosticLog() {
        val errorMessages = ShadowLog.getLogsForTag("LyricsController")
            .filter { it.type == Log.ERROR }
            .map { it.msg }
        assertTrue(errorMessages.any { it.contains("INDEX_WRITE") })
        assertTrue(errorMessages.any { it.contains("IO_OPERATION_RETURNED_FALSE") })
        assertTrue(errorMessages.any { it.contains("RESTORE_INDEX") })
        assertTrue(errorMessages.any { it.contains("RESTORE_WORD_BY_WORD_FILE") })
    }

    private fun resetUiState(keepToastMuted: Boolean = false) {
        dialogHost.dismissAll()
        ShadowToast.reset()
        ShadowLog.clear()
        if (!keepToastMuted) {
            AppSettingsStore.setToasterMuted(activity, false)
        }
    }

    private fun shownToastTexts(): List<String> {
        if (ShadowToast.shownToastCount() == 0) return emptyList()
        return listOf(ShadowToast.getTextOfLatestToast())
    }

    private fun successErrorToastTexts(): Set<String> {
        return setOf(
            activity.getString(R.string.ui_lrc_file_too_large),
            activity.getString(R.string.ui_word_by_word_blocked_by_plain_lrc),
            activity.getString(R.string.ui_plain_lrc_blocked_by_word_by_word),
            activity.getString(R.string.ui_cannot_read_word_by_word_lyrics_file),
            activity.getString(R.string.ui_cannot_read_this_lyric_file),
            activity.getString(R.string.ui_lrc_import_save_failed)
        )
    }

    private fun rollbackFailedResult(): LyricsStorage.ImportLyricsResult.RollbackFailed {
        return LyricsStorage.ImportLyricsResult.RollbackFailed(
            originalFailureStep = LyricsStorage.WordByWordImportFailureStep.INDEX_WRITE,
            originalFailureCause = LyricsStorage.WordByWordImportFailureCause.IO_OPERATION_RETURNED_FALSE,
            failedRollbackSteps = listOf(
                LyricsStorage.WordByWordRollbackFailureStep.RESTORE_INDEX,
                LyricsStorage.WordByWordRollbackFailureStep.RESTORE_WORD_BY_WORD_FILE
            )
        )
    }

    private fun unexpectedOverwriteRequester(): LyricsOverwriteConfirmationRequester {
        return LyricsOverwriteConfirmationRequester { request ->
            error("Unexpected overwrite confirmation: $request")
        }
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
        return Uri.fromFile(File(activity.cacheDir, name).apply { writeText(text) })
    }

    private fun resetStorage() {
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val base = activity.getExternalFilesDir(null) ?: activity.filesDir
        File(base, FALLBACK_LYRICS_DIR).deleteRecursively()
    }

    private fun View.descendantTexts(): List<String> {
        val ownText = (this as? TextView)?.text?.toString()?.let(::listOf).orEmpty()
        if (this !is ViewGroup) return ownText
        return ownText + (0 until childCount).flatMap { getChildAt(it).descendantTexts() }
    }

    private data class SuccessCase(
        val wordByWordLyrics: Boolean,
        val target: SongIdentity,
        val source: String,
        val expectedToast: String
    )

    private data class FailureCase(
        val name: String,
        val result: LyricsStorage.ImportLyricsResult,
        val wordByWordLyrics: Boolean = false,
        val expectedToastRes: Int? = null,
        val expectedDialog: Boolean = false
    )

    private data class LyricsControllerDependencies(
        val runner: ControlledTaskRunner,
        val invalidator: RecordingInvalidator,
        val reloader: RecordingReloader
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

    private class ControlledTaskRunner : MainTaskRunner {
        private val ioTasks = ArrayDeque<() -> Unit>()
        private val mainTasks = ArrayDeque<() -> Unit>()

        override fun runOnAppIo(block: () -> Unit) {
            ioTasks.addLast(block)
        }

        override fun runOnMainThread(block: () -> Unit) {
            mainTasks.addLast(block)
        }

        fun drain() {
            while (ioTasks.isNotEmpty() || mainTasks.isNotEmpty()) {
                while (ioTasks.isNotEmpty()) ioTasks.removeFirst().invoke()
                while (mainTasks.isNotEmpty()) mainTasks.removeFirst().invoke()
            }
        }
    }

    private class ProductionDialogHost(
        private val activity: MainActivity
    ) : MainDialogHost {
        val shownDialogs = mutableListOf<Dialog>()

        override fun showConfirmDialog(
            title: String,
            message: String,
            positiveText: String,
            onPositive: () -> Unit,
            onNegative: () -> Unit
        ) {
            error("Unexpected overwrite confirmation: $title")
        }

        override fun showInfoDialog(title: String, message: String) {
            shownDialogs += activity.graph.uiHost.showAirInfoDialog(title, message)
        }

        fun dismissAll() {
            shownDialogs.forEach { dialog ->
                if (dialog.isShowing) dialog.dismiss()
            }
            shownDialogs.clear()
        }
    }

    private class RecordingReloader {
        val commands = mutableListOf<String>()
    }

    private class RecordingLyricsChangedPublisher : LyricsChangedPublisher {
        val changes = mutableListOf<LyricsChange>()
        val targets: List<SongIdentity>
            get() = changes.mapNotNull(LyricsChange::target)

        override fun publish(change: LyricsChange) {
            changes += change
        }
    }

    private class RecordingInvalidator : UiInvalidator {
        val rebuildCalls = mutableListOf<RebuildCall>()

        override fun rebuildCurrentPage(
            reason: PageRebuildReason,
            animateContent: Boolean,
            animateTabs: Boolean
        ) {
            rebuildCalls += RebuildCall(reason, animateContent, animateTabs)
        }

        override fun refreshTabs(animate: Boolean) = Unit
        override fun refreshFloatingChrome() = Unit
        override fun refreshFloatingControls() = Unit
        override fun refreshLyricsSettingsContent() = Unit
        override fun recreateMainView(reason: PageRebuildReason) = Unit
    }

    private data class RebuildCall(
        val reason: PageRebuildReason,
        val animateContent: Boolean,
        val animateTabs: Boolean
    )

    private class EmptyMediaControllerProvider : MediaControllerProvider {
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
