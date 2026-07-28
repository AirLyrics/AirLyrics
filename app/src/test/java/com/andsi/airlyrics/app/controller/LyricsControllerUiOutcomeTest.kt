package com.andsi.airlyrics.app.controller

import android.app.Dialog
import android.content.Context
import android.media.session.MediaController
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
import com.andsi.airlyrics.lyrics.storage.FALLBACK_LYRICS_DIR
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.lyrics.storage.PREFS_NAME
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
        AppSettingsStore.setToasterMuted(activity, false)
        ShadowToast.reset()
        ShadowLog.clear()
        resetStorage()
        activityController.close()
    }

    @Test
    fun savedImport_showsSuccessReloadsFloatingAndInvalidatesLyricsPage() {
        val cases = listOf(
            SuccessCase(
                wordByWord = false,
                target = target("Plain success"),
                source = "[00:10.00]plain success",
                expectedToast = activity.getString(R.string.ui_plain_lrc_import_success)
            ),
            SuccessCase(
                wordByWord = true,
                target = target("Word-by-word success"),
                source = "[00:10.00]<00:10.00>word-by-word success",
                expectedToast = activity.getString(R.string.ui_enhanced_lrc_import_success)
            )
        )

        cases.forEachIndexed { index, case ->
            resetUiState()
            val runner = ControlledTaskRunner()
            val invalidator = RecordingInvalidator()
            val reloader = RecordingReloader()
            val controller = controller(runner, invalidator, reloader)

            controller.importLyricsForTarget(
                uri = writeImportFile("success-$index.lrc", case.source),
                target = case.target,
                overwrite = false,
                importAsWordByWord = case.wordByWord
            )
            runner.drain()

            val toastTexts = shownToastTexts()
            assertEquals(case.expectedToast, toastTexts.last())
            assertFalse(successErrorToastTexts().any(ShadowToast::showedToast))
            assertTrue(dialogHost.shownDialogs.isEmpty())
            assertEquals(listOf("reloadFloatingLyrics"), reloader.commands)
            assertEquals(
                listOf(
                    RebuildCall(
                        reason = PageRebuildReason.LYRICS_CHANGED,
                        animateContent = false,
                        animateTabs = false
                    )
                ),
                invalidator.rebuildCalls
            )
            if (case.wordByWord) {
                assertTrue(
                    LyricsStorage.hasKaraokeLyrics(
                        activity,
                        case.target.title,
                        case.target.artist,
                        case.target.durationMs
                    )
                )
            } else {
                assertTrue(
                    LyricsStorage.hasLocalLyrics(
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
                wordByWord = true,
                expectedToastRes = R.string.ui_enhanced_lrc_blocked_by_plain_lrc
            ),
            FailureCase(
                name = "WordByWordLyricsAlreadyExists",
                result = LyricsStorage.ImportLyricsResult.WordByWordLyricsAlreadyExists,
                expectedToastRes = R.string.ui_plain_lrc_blocked_by_enhanced_lrc
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
            val controller = controller(
                runner = runner,
                invalidator = invalidator,
                reloader = reloader,
                gateway = FixedResultLyricsImportGateway(case.result)
            )

            controller.importLyricsForTarget(
                uri = Uri.parse("content://lyrics-outcome/${case.name}"),
                target = target("Failure $index"),
                overwrite = true,
                importAsWordByWord = case.wordByWord
            )
            runner.drain()

            assertFailureHasNoSuccessSideEffects(invalidator, reloader)
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
            val controller = controller(
                runner = runner,
                invalidator = invalidator,
                reloader = reloader,
                gateway = FixedResultLyricsImportGateway(case.result)
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

    private fun controller(
        runner: ControlledTaskRunner,
        invalidator: RecordingInvalidator,
        reloader: RecordingReloader,
        gateway: LyricsImportGateway? = null
    ): LyricsController {
        val common = LyricsControllerDependencies(
            runner = runner,
            invalidator = invalidator,
            reloader = reloader
        )
        return if (gateway == null) {
            LyricsController(
                context = activity,
                invalidator = common.invalidator,
                taskRunner = common.runner,
                dialogHost = dialogHost,
                mediaControllerProvider = EmptyMediaControllerProvider(),
                floatingLyricsReloader = common.reloader,
                overwriteConfirmationRequester = unexpectedOverwriteRequester()
            )
        } else {
            LyricsController(
                context = activity,
                invalidator = common.invalidator,
                taskRunner = common.runner,
                dialogHost = dialogHost,
                mediaControllerProvider = EmptyMediaControllerProvider(),
                floatingLyricsReloader = common.reloader,
                overwriteConfirmationRequester = unexpectedOverwriteRequester(),
                lyricsImportGateway = gateway
            )
        }
    }

    private fun assertFailureHasNoSuccessSideEffects(
        invalidator: RecordingInvalidator,
        reloader: RecordingReloader
    ) {
        assertFalse(ShadowToast.showedToast(activity.getString(R.string.ui_plain_lrc_import_success)))
        assertFalse(ShadowToast.showedToast(activity.getString(R.string.ui_enhanced_lrc_import_success)))
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
        assertTrue(errorMessages.any { it.contains("RESTORE_KARAOKE_FILE") })
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
            activity.getString(R.string.ui_enhanced_lrc_blocked_by_plain_lrc),
            activity.getString(R.string.ui_plain_lrc_blocked_by_enhanced_lrc),
            activity.getString(R.string.ui_cannot_read_enhanced_lrc_file),
            activity.getString(R.string.ui_cannot_read_this_lyric_file),
            activity.getString(R.string.ui_lrc_import_save_failed)
        )
    }

    private fun rollbackFailedResult(): LyricsStorage.ImportLyricsResult.RollbackFailed {
        return LyricsStorage.ImportLyricsResult.RollbackFailed(
            originalFailureStep = LyricsStorage.KaraokeImportFailureStep.INDEX_WRITE,
            originalFailureCause = LyricsStorage.KaraokeImportFailureCause.IO_OPERATION_RETURNED_FALSE,
            failedRollbackSteps = listOf(
                LyricsStorage.KaraokeRollbackFailureStep.RESTORE_INDEX,
                LyricsStorage.KaraokeRollbackFailureStep.RESTORE_KARAOKE_FILE
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
        val wordByWord: Boolean,
        val target: SongIdentity,
        val source: String,
        val expectedToast: String
    )

    private data class FailureCase(
        val name: String,
        val result: LyricsStorage.ImportLyricsResult,
        val wordByWord: Boolean = false,
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

    private class RecordingReloader : com.andsi.airlyrics.app.contracts.FloatingLyricsReloader {
        val commands = mutableListOf<String>()

        override fun reloadFloatingLyrics() {
            commands += "reloadFloatingLyrics"
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
}
