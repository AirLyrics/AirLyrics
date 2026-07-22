package com.andsi.airlyrics.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.app.state.LyricsImportType
import com.andsi.airlyrics.app.state.PendingLyricsImport
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.storage.FALLBACK_LYRICS_DIR
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.lyrics.storage.PREFS_NAME
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.media.toSongIdentity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
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
    }

    @After
    fun tearDown() {
        activityController?.close()
        mediaSession?.release()
        clearCurrentMedia()
        resetStorage()
    }

    @Test
    fun pendingImport_survivesActivityRecreation() {
        val controller = launchActivity()
        val request = pendingImport(SONG_A)
        controller.get().graph.state.pendingLyricsImport = request

        controller.recreate()

        assertEquals(request, controller.get().graph.state.pendingLyricsImport)
    }

    @Test
    fun cancelledFilePicker_consumesPendingImport() {
        val activity = launchActivity().get()
        activity.graph.state.pendingLyricsImport = pendingImport(SONG_A)

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
    fun selectedFile_thenActivityDestroy_importsCapturedSongA_notCurrentSongB() {
        val controller = launchActivity()
        val activity = controller.get()
        installCurrentMedia(activity, SONG_B)
        assertEquals(
            SONG_B,
            activity.graph.lyricsController.getCurrentMediaInfo()?.toSongIdentity()
        )
        activity.graph.state.pendingLyricsImport = pendingImport(SONG_A)
        val releaseBlocker = CountDownLatch(1)
        val blockerStarted = CountDownLatch(1)
        activity.graph.runOnAppIo {
            blockerStarted.countDown()
            releaseBlocker.await(5, TimeUnit.SECONDS)
        }
        assertTrue("Timed out waiting for the I/O blocker", blockerStarted.await(5, TimeUnit.SECONDS))

        activity.graph.launchers.selectLyricsFile()
        val pickerRequest = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(
            pickerRequest.intent,
            Activity.RESULT_OK,
            Intent().setData(writeImportFile())
        )
        ShadowLooper.idleMainLooper()
        val importCompleted = CountDownLatch(1)
        activity.graph.runOnAppIo { importCompleted.countDown() }

        controller.close()
        activityController = null
        releaseBlocker.countDown()

        assertTrue("Timed out waiting for queued import", importCompleted.await(5, TimeUnit.SECONDS))
        assertTrue(hasPlainLyrics(SONG_A))
        assertFalse(hasPlainLyrics(SONG_B))
    }

    private fun launchActivity(): ActivityController<MainActivity> {
        return Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .also { activityController = it }
    }

    private fun installCurrentMedia(activity: MainActivity, song: SongIdentity) {
        val session = MediaSession(activity, "lyrics-import-current-media-test")
        mediaSession = session
        val controller = MediaController(activity, session.sessionToken)
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
        val manager = activity.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        shadowOf(manager).addController(controller)
        MediaSourceStore.saveSelectedPackage(activity, CURRENT_MEDIA_PACKAGE)
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
        return LyricsStorage.hasLocalLyrics(
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
