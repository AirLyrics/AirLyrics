package com.andsi.airlyrics.app

import android.app.Activity
import android.app.Dialog
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
import com.andsi.airlyrics.lyrics.BroadcastLyricsChangedPublisher
import com.andsi.airlyrics.lyrics.storage.FALLBACK_LYRICS_DIR
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.lyrics.storage.PREFS_NAME
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.media.toSongIdentity
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowDialog

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
    fun pendingImport_survivesActivityRecreation() {
        val controller = launchActivity()
        val request = pendingImport(SONG_A)
        controller.get().graph.viewModel.setPendingLyricsImport(request)

        controller.recreate()

        assertEquals(request, controller.get().graph.state.pendingLyricsImport)
    }

    @Test
    fun cancelledFilePicker_consumesPendingImport() {
        val activity = launchActivity().get()
        activity.graph.viewModel.setPendingLyricsImport(pendingImport(SONG_A))

        activity.graph.launchers.selectLyricsFile()
        val pickerRequest = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(
            pickerRequest.intent,
            Activity.RESULT_CANCELED,
            null
        )
        ShadowLooper.idleMainLooper()

        assertNull(activity.graph.state.pendingLyricsImport)
        assertFalse(hasPlainLyrics(SONG_A))
    }

    @Test
    fun selectedFile_importsCapturedSongA_notCurrentSongB() {
        val activity = launchActivity().get()
        installCurrentMedia(activity, SONG_B)
        assertEquals(
            SONG_B,
            activity.graph.viewModel.currentMediaInfo()?.toSongIdentity()
        )
        deliverPickerResult(activity, writeImportFile(), SONG_A)

        awaitCondition("Timed out waiting for captured lyrics import") {
            hasPlainLyrics(SONG_A)
        }
        assertTrue(hasPlainLyrics(SONG_A))
        assertFalse(hasPlainLyrics(SONG_B))
    }

    @Test
    fun importCompletesAfterRecreation_liveGraphRefreshesWithoutDestroyedActivityUi() {
        installCurrentMedia(context, SONG_A)
        val inputOpenCount = AtomicInteger()
        registerLyricsInput(LATE_IMPORT_URI, inputOpenCount)
        val controller = launchActivity()
        val oldActivity = controller.get()
        showLyricsSettings(oldActivity)
        awaitAppIo(oldActivity)
        assertTrue(oldActivity.visibleTexts().contains(oldActivity.getString(R.string.ui_not_bound)))

        deliverPickerResult(oldActivity, LATE_IMPORT_URI, SONG_A)

        val oldRenderedPage = oldActivity.graph.uiHost.contentContainer?.getChildAt(0)
        controller.recreate()
        val newActivity = controller.get()
        ShadowDialog.reset()

        awaitCondition("Timed out waiting for retained ViewModel import") {
            LyricsStorage.hasPlainLyrics(
                context = newActivity,
                title = SONG_A.title,
                artist = SONG_A.artist,
                duration = SONG_A.durationMs
            )
        }
        awaitCondition("Timed out waiting for live lyrics UI refresh") {
            !newActivity.visibleTexts().contains(newActivity.getString(R.string.ui_not_bound))
        }

        assertEquals(1, inputOpenCount.get())
        assertEquals(
            "[00:01.00]late durable lyrics",
            LyricsStorage.readPlainLyrics(
                context = newActivity,
                title = SONG_A.title,
                artist = SONG_A.artist,
                duration = SONG_A.durationMs
            )
        )
        assertFalse(newActivity.visibleTexts().contains(newActivity.getString(R.string.ui_not_bound)))
        assertTrue(newActivity.visibleTexts().contains("${SONG_A.title} - ${SONG_A.artist}"))
        assertTrue(oldActivity.isDestroyed)
        assertSame(oldRenderedPage, oldActivity.graph.uiHost.contentContainer?.getChildAt(0))
        val latestDialog: Dialog? = ShadowDialog.getLatestDialog()
        assertNull(latestDialog)
    }

    @Test
    fun lyricsChangedBeforeActivityCreation_initialRenderReadsDurableLyrics() {
        installCurrentMedia(context, SONG_A)
        assertTrue(
            LyricsStorage.savePlainLyrics(
                context = context,
                title = SONG_A.title,
                artist = SONG_A.artist,
                duration = SONG_A.durationMs,
                album = SONG_A.album,
                plainLrc = "[00:01.00]early durable lyrics",
                plainProvider = "early-event-test"
            )
        )
        BroadcastLyricsChangedPublisher(context).publish(SONG_A)
        ShadowLooper.idleMainLooper()

        val restoredNavigation = Bundle().apply {
            putString("airlyrics.current_page", Page.SETTINGS.name)
            putString("airlyrics.settings_sub_page", SettingsSubPage.LYRICS.name)
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java)
            .create(restoredNavigation)
            .start()
            .resume()
            .visible()
            .also { activityController = it }
        val activity = controller.get()
        awaitAppIo(activity)

        assertFalse(activity.visibleTexts().contains(activity.getString(R.string.ui_not_bound)))
        assertTrue(activity.visibleTexts().contains("${SONG_A.title} - ${SONG_A.artist}"))
        assertEquals(
            "[00:01.00]early durable lyrics",
            LyricsStorage.readPlainLyrics(
                context = activity,
                title = SONG_A.title,
                artist = SONG_A.artist,
                duration = SONG_A.durationMs
            )
        )
    }

    @Test
    fun lyricsChanged_refreshesMountedCardsWithoutReplacingSettingsPage() {
        installCurrentMedia(context, SONG_A)
        val controller = launchActivity()
        val activity = controller.get()
        showLyricsSettings(activity)
        awaitAppIo(activity)
        val mountedPage = activity.graph.uiHost.contentContainer?.getChildAt(0)

        assertTrue(
            LyricsStorage.savePlainLyrics(
                context = activity,
                title = SONG_A.title,
                artist = SONG_A.artist,
                duration = SONG_A.durationMs,
                album = SONG_A.album,
                plainLrc = "[00:01.00]targeted refresh lyrics"
            )
        )
        BroadcastLyricsChangedPublisher(activity).publish(SONG_A)
        awaitCondition("Timed out waiting for mounted lyrics refresh") {
            !activity.visibleTexts().contains(activity.getString(R.string.ui_not_bound))
        }

        assertSame(mountedPage, activity.graph.uiHost.contentContainer?.getChildAt(0))
        assertFalse(activity.visibleTexts().contains(activity.getString(R.string.ui_not_bound)))
    }

    private fun launchActivity(): ActivityController<MainActivity> {
        return Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .also { activityController = it }
    }

    @Suppress("UsePropertyAccessSyntax")
    private fun installCurrentMedia(context: Context, song: SongIdentity) {
        val session = MediaSession(context, "lyrics-import-current-media-test")
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
        activity.graph.viewModel.setPendingLyricsImport(pendingImport(target))
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

    private fun pendingImport(song: SongIdentity): PendingLyricsImport {
        return PendingLyricsImport(
            target = song,
            type = LyricsImportType.PLAIN
        )
    }

    private fun writeImportFile(): Uri {
        return Uri.fromFile(
            File(context.cacheDir, "captured-song-a.lrc").apply {
                writeText("[00:01.00]captured song A")
            }
        )
    }

    private fun hasPlainLyrics(song: SongIdentity): Boolean {
        return LyricsStorage.hasPlainLyrics(
            context = context,
            title = song.title,
            artist = song.artist,
            duration = song.durationMs
        )
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
        val LATE_IMPORT_URI: Uri = Uri.parse("content://lyrics-lifecycle/late-song-a.lrc")

        val SONG_A = SongIdentity(
            title = "Captured Song A",
            artist = "Artist A",
            album = "Album A",
            durationMs = 100_000L
        )

        val SONG_B = SongIdentity(
            title = "Current Song B",
            artist = "Artist B",
            album = "Album B",
            durationMs = 200_000L
        )
    }
}
