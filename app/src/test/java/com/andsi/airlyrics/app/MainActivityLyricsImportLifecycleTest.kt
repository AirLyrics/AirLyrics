package com.andsi.airlyrics.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.state.LyricsImportType
import com.andsi.airlyrics.app.state.PendingLyricsImport
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.storage.FALLBACK_LYRICS_DIR
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.lyrics.storage.PREFS_NAME
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.settings.store.AppSettingsStore
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
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

@RunWith(RobolectricTestRunner::class)
class MainActivityLyricsImportLifecycleTest {
    private lateinit var context: Context
    private var activityController: ActivityController<MainActivity>? = null
    private var mediaSession: MediaSession? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetStorage()
        clearCurrentMedia()
        AppSettingsStore.setStatusPopupsMuted(context, false)
        ShadowContentResolver.reset()
        ShadowDialog.reset()
    }

    @After
    fun tearDown() {
        activityController?.close()
        mediaSession?.release()
        clearCurrentMedia()
        AppSettingsStore.setStatusPopupsMuted(context, false)
        ShadowContentResolver.reset()
        ShadowDialog.reset()
        resetStorage()
    }

    @Test
    fun pendingImport_restoresIntoNewViewModelFromSavedInstanceState() {
        val originalController = launchActivity()
        val request = PendingLyricsImport(SONG, LyricsImportType.PLAIN)
        val originalActivity = originalController.get()
        val originalViewModel = originalActivity.graph.viewModel
        originalViewModel.setPendingLyricsImport(request)

        val savedInstanceState = Bundle()
        originalController
            .pause()
            .saveInstanceState(savedInstanceState)
            .stop()
            .destroy()

        val restoredController = Robolectric.buildActivity(MainActivity::class.java)
            .create(savedInstanceState)
            .start()
            .resume()
            .visible()
            .also { activityController = it }
        val restoredActivity = restoredController.get()

        assertTrue(originalActivity.isDestroyed)
        assertNotSame(originalViewModel, restoredActivity.graph.viewModel)
        assertEquals(request, restoredActivity.graph.state.pendingLyricsImport)
    }

    @Test
    fun importCompletesAfterRecreation_updatesOnlyTheRestoredActivity() {
        installCurrentMedia(SONG)
        val inputOpenCount = AtomicInteger()
        registerLyricsInput(LATE_IMPORT_URI, inputOpenCount)
        val controller = launchActivity()
        val oldActivity = controller.get()
        showLyricsSettings(oldActivity)
        awaitAppIo(oldActivity)
        assertTrue(oldActivity.visibleTexts().contains(oldActivity.getString(R.string.ui_not_bound)))

        deliverPickerResult(oldActivity, LATE_IMPORT_URI, SONG)
        controller.recreate()
        val restoredActivity = controller.get()
        ShadowDialog.reset()

        awaitCondition("Timed out waiting for retained import") {
            LyricsStorage.hasPlainLyrics(
                context = restoredActivity,
                title = SONG.title,
                artist = SONG.artist,
                duration = SONG.durationMs
            )
        }
        awaitCondition("Timed out waiting for restored lyrics UI") {
            !restoredActivity.visibleTexts().contains(
                restoredActivity.getString(R.string.ui_not_bound)
            )
        }

        assertEquals(1, inputOpenCount.get())
        assertEquals(
            "[00:01.00]late durable lyrics",
            LyricsStorage.readPlainLyrics(
                context = restoredActivity,
                title = SONG.title,
                artist = SONG.artist,
                duration = SONG.durationMs
            )
        )
        assertTrue(restoredActivity.visibleTexts().contains("${SONG.title} - ${SONG.artist}"))
        assertTrue(oldActivity.isDestroyed)
        assertNull(ShadowDialog.getLatestDialog())
    }

    private fun launchActivity(): ActivityController<MainActivity> {
        return Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .also { activityController = it }
    }

    @Suppress("UsePropertyAccessSyntax")
    private fun installCurrentMedia(song: SongIdentity) {
        val session = MediaSession(context, "lyrics-import-lifecycle-test")
        mediaSession = session
        val controller = MediaController(context, session.sessionToken)
        shadowOf(controller).apply {
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
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        shadowOf(manager).addController(controller)
        MediaSourceStore.saveSelectedPackage(context, CURRENT_MEDIA_PACKAGE)
    }

    private fun showLyricsSettings(activity: MainActivity) {
        activity.graph.viewModel.selectPage(Page.SETTINGS)
        activity.graph.viewModel.openSettingsSubPage(SettingsSubPage.LYRICS)
        activity.graph.uiInvalidator.rebuildCurrentPage(
            animateContent = false,
            animateTabs = false
        )
    }

    private fun deliverPickerResult(activity: MainActivity, uri: Uri, target: SongIdentity) {
        activity.graph.viewModel.setPendingLyricsImport(
            PendingLyricsImport(target, LyricsImportType.PLAIN)
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

    private fun registerLyricsInput(uri: Uri, openCount: AtomicInteger) {
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            openCount.incrementAndGet()
            ByteArrayInputStream("[00:01.00]late durable lyrics".toByteArray())
        }
    }

    private fun awaitAppIo(activity: MainActivity) {
        val completed = CountDownLatch(1)
        activity.graph.runOnAppIo { completed.countDown() }
        assertTrue("Timed out waiting for app I/O", completed.await(5, TimeUnit.SECONDS))
        ShadowLooper.idleMainLooper()
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()
        assertTrue(message, condition())
    }

    private fun MainActivity.visibleTexts(): List<String> {
        return findViewById<View>(android.R.id.content).descendantTexts()
    }

    private fun View.descendantTexts(): List<String> {
        val ownText = (this as? TextView)?.text?.toString()?.let(::listOf).orEmpty()
        if (this !is ViewGroup) return ownText
        return ownText + (0 until childCount).flatMap { getChildAt(it).descendantTexts() }
    }

    private fun clearCurrentMedia() {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        shadowOf(manager).clearControllers()
        MediaSourceStore.saveSelectedPackage(context, null)
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
        const val CURRENT_MEDIA_PACKAGE = "player.current"
        val LATE_IMPORT_URI: Uri = Uri.parse("content://lyrics-lifecycle/late-song.lrc")
        val SONG = SongIdentity(
            title = "Captured Song",
            artist = "Artist",
            album = "Album",
            durationMs = 100_000L
        )
    }
}
