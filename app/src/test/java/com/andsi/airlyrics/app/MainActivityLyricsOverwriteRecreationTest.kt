package com.andsi.airlyrics.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.state.LyricsImportType
import com.andsi.airlyrics.app.state.PendingLyricsImport
import com.andsi.airlyrics.app.state.PendingLyricsOverwrite
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.storage.FALLBACK_LYRICS_DIR
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.lyrics.storage.PREFS_NAME
import com.andsi.airlyrics.settings.store.AppSettingsStore
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
class MainActivityLyricsOverwriteRecreationTest {
    private lateinit var context: Context
    private var activityController: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetStorage()
        AppSettingsStore.setToasterMuted(context, false)
        ShadowContentResolver.reset()
        ShadowDialog.reset()
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        activityController?.close()
        activityController = null
        AppSettingsStore.setToasterMuted(context, false)
        ShadowContentResolver.reset()
        ShadowDialog.reset()
        ShadowToast.reset()
        resetStorage()
    }

    @Test
    fun overwriteConfirmation_activityRecreated_preservesOriginalRequestAndImportsExactlyOnce() {
        saveExistingWordByWordLyrics(SONG_A)
        val inputOpenCount = AtomicInteger()
        registerLyricsInput(ORIGINAL_URI, inputOpenCount, "new")
        val controller = launchActivity()
        val oldActivity = controller.get()

        deliverPickerResult(
            activity = oldActivity,
            uri = ORIGINAL_URI,
            target = SONG_A
        )
        awaitAppIo(oldActivity)

        val oldDialog = requireOverwriteDialog(oldActivity)
        val oldPositiveButton = requireDialogButton(oldDialog, oldActivity.getString(R.string.ui_overwrite))
        assertEquals(0, inputOpenCount.get())

        controller.recreate()
        ShadowLooper.idleMainLooper()

        val newActivity = controller.get()
        val restoredDialog = requireOverwriteDialog(newActivity)
        assertNotSame(oldDialog, restoredDialog)
        assertEquals(
            PendingLyricsOverwrite(
                uri = ORIGINAL_URI,
                target = SONG_A,
                type = LyricsImportType.WORD_BY_WORD
            ),
            newActivity.graph.state.pendingLyricsOverwrite
        )

        oldPositiveButton.performClick()
        assertEquals("The destroyed Activity must not submit its stale request", 0, inputOpenCount.get())

        requireDialogButton(restoredDialog, newActivity.getString(R.string.ui_overwrite)).performClick()
        awaitAppIo(newActivity)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)

        assertEquals(1, inputOpenCount.get())
        assertNull(newActivity.graph.state.pendingLyricsOverwrite)
        assertEquals(
            "[00:10.00]new",
            LyricsStorage.readLocalLyrics(
                context = newActivity,
                title = SONG_A.title,
                artist = SONG_A.artist,
                duration = SONG_A.durationMs
            )
        )
        assertFalse(
            LyricsStorage.hasKaraokeLyrics(
                context = newActivity,
                title = SONG_B.title,
                artist = SONG_B.artist,
                duration = SONG_B.durationMs
            )
        )

        ShadowDialog.reset()
        controller.recreate()
        ShadowLooper.idleMainLooper()
        awaitAppIo(controller.get())

        assertNull(ShadowDialog.getLatestDialog())
        assertNull(controller.get().graph.state.pendingLyricsOverwrite)
        assertEquals(1, inputOpenCount.get())
    }

    @Test
    fun overwriteConfirmation_cancelThenRecreate_doesNotRestoreOrImport() {
        saveExistingWordByWordLyrics(SONG_A)
        val inputOpenCount = AtomicInteger()
        registerLyricsInput(ORIGINAL_URI, inputOpenCount, "cancelled")
        val controller = launchActivity()
        val activity = controller.get()

        deliverPickerResult(
            activity = activity,
            uri = ORIGINAL_URI,
            target = SONG_A
        )
        awaitAppIo(activity)

        val dialog = requireOverwriteDialog(activity)
        requireDialogButton(dialog, activity.getString(R.string.ui_cancel)).performClick()
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)

        assertNull(activity.graph.state.pendingLyricsOverwrite)
        assertEquals(0, inputOpenCount.get())

        ShadowDialog.reset()
        controller.recreate()
        ShadowLooper.idleMainLooper()
        awaitAppIo(controller.get())

        assertNull(ShadowDialog.getLatestDialog())
        assertNull(controller.get().graph.state.pendingLyricsOverwrite)
        assertEquals(0, inputOpenCount.get())
        assertEquals(
            "[00:10.00]old",
            LyricsStorage.readLocalLyrics(
                context = controller.get(),
                title = SONG_A.title,
                artist = SONG_A.artist,
                duration = SONG_A.durationMs
            )
        )
    }

    @Test
    fun overwriteConfirmation_restoredUnreadableUri_consumesRequestAndShowsReadFailedOnce() {
        saveExistingWordByWordLyrics(SONG_A)
        val inputOpenCount = AtomicInteger()
        shadowOf(context.contentResolver).registerInputStreamSupplier(ORIGINAL_URI) {
            inputOpenCount.incrementAndGet()
            throw SecurityException("Expired URI permission")
        }
        val controller = launchActivity()
        val activity = controller.get()

        deliverPickerResult(
            activity = activity,
            uri = ORIGINAL_URI,
            target = SONG_A
        )
        awaitAppIo(activity)
        requireOverwriteDialog(activity)

        controller.recreate()
        ShadowLooper.idleMainLooper()
        val restoredActivity = controller.get()
        val restoredDialog = requireOverwriteDialog(restoredActivity)
        requireDialogButton(
            restoredDialog,
            restoredActivity.getString(R.string.ui_overwrite)
        ).performClick()
        awaitAppIo(restoredActivity)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)

        assertEquals(1, inputOpenCount.get())
        assertNull(restoredActivity.graph.state.pendingLyricsOverwrite)
        assertEquals(
            restoredActivity.getString(R.string.ui_cannot_read_enhanced_lrc_file),
            ShadowToast.getTextOfLatestToast()
        )
        assertEquals(
            "[00:10.00]old",
            LyricsStorage.readLocalLyrics(
                context = restoredActivity,
                title = SONG_A.title,
                artist = SONG_A.artist,
                duration = SONG_A.durationMs
            )
        )

        ShadowDialog.reset()
        controller.recreate()
        ShadowLooper.idleMainLooper()
        awaitAppIo(controller.get())

        assertNull(ShadowDialog.getLatestDialog())
        assertEquals(1, inputOpenCount.get())
    }

    private fun launchActivity(): ActivityController<MainActivity> {
        return Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .also { activityController = it }
    }

    private fun deliverPickerResult(
        activity: MainActivity,
        uri: Uri,
        target: SongIdentity
    ) {
        activity.graph.state.pendingLyricsImport = PendingLyricsImport(
            target,
            LyricsImportType.WORD_BY_WORD
        )
        activity.graph.launchers.selectLyricsFile()
        val pickerRequest = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(
            pickerRequest.intent,
            Activity.RESULT_OK,
            Intent()
                .setData(uri)
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
        )
        ShadowLooper.idleMainLooper()
    }

    private fun awaitAppIo(activity: MainActivity) {
        val completed = CountDownLatch(1)
        activity.graph.runOnAppIo { completed.countDown() }
        assertTrue("Timed out waiting for app I/O", completed.await(5, TimeUnit.SECONDS))
        ShadowLooper.idleMainLooper()
    }

    private fun requireOverwriteDialog(activity: MainActivity): Dialog {
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("Expected overwrite confirmation dialog", dialog)
        assertTrue(dialog!!.isShowing)
        val texts = dialog.window?.decorView?.descendantTexts().orEmpty()
        assertTrue(texts.any { it.contains(SONG_A.title) && it.contains(SONG_A.artist) })
        assertTrue(texts.contains(activity.getString(R.string.ui_overwrite_local_enhanced_lrc)))
        return dialog
    }

    private fun requireDialogButton(dialog: Dialog, text: String): TextView {
        val button = dialog.window?.decorView?.findTextView(text)
        assertNotNull("Missing dialog button: $text", button)
        return button!!
    }

    private fun registerLyricsInput(uri: Uri, openCount: AtomicInteger, word: String) {
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            openCount.incrementAndGet()
            ByteArrayInputStream("[00:10.00]<00:10.00>$word".toByteArray())
        }
    }

    private fun saveExistingWordByWordLyrics(song: SongIdentity) {
        val uri = Uri.fromFile(
            File(context.cacheDir, "existing-${song.title}.lrc").apply {
                writeText("[00:10.00]<00:10.00>old")
            }
        )
        assertTrue(
            LyricsStorage.importKaraokeLyricsFromUriWithResult(
                context = context,
                uri = uri,
                title = song.title,
                artist = song.artist,
                duration = song.durationMs,
                album = song.album
            ) is LyricsStorage.ImportLyricsResult.Saved
        )
    }

    private fun View.descendantTexts(): List<String> {
        val ownText = (this as? TextView)?.text?.toString()?.let(::listOf).orEmpty()
        if (this !is ViewGroup) return ownText
        return ownText + (0 until childCount).flatMap { getChildAt(it).descendantTexts() }
    }

    private fun View.findTextView(text: String): TextView? {
        if (this is TextView && this.text.toString() == text) return this
        if (this !is ViewGroup) return null
        return (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findTextView(text) }
    }

    private fun resetStorage() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        File(base, FALLBACK_LYRICS_DIR).deleteRecursively()
    }

    private companion object {
        val ORIGINAL_URI: Uri = Uri.parse("content://lyrics-recreation/original-song-a.lrc")

        val SONG_A = SongIdentity(
            title = "Original Song A",
            artist = "Original Artist A",
            album = "Original Album A",
            durationMs = 185_000L
        )

        val SONG_B = SongIdentity(
            title = "Other Song B",
            artist = "Other Artist B",
            album = "Other Album B",
            durationMs = 240_000L
        )
    }
}
