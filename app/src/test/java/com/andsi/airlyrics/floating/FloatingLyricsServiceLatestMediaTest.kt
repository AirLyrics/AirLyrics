package com.andsi.airlyrics.floating

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.BroadcastLyricsChangedPublisher
import com.andsi.airlyrics.lyrics.LyricsLookupCallbackDispatcher
import com.andsi.airlyrics.lyrics.LyricsLookupRunner
import com.andsi.airlyrics.lyrics.createLyricsLookupExecutor
import com.andsi.airlyrics.lyrics.storage.FALLBACK_LYRICS_DIR
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.lyrics.storage.PREFS_NAME
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class FloatingLyricsServiceLatestMediaTest {
    private lateinit var context: Context
    private var serviceController: ServiceController<out FloatingLyricsService>? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetState()
        ShadowSettings.setCanDrawOverlays(true)
        MediaSourceStore.saveSelectedPackage(context, SOURCE_PACKAGE)
    }

    @After
    fun tearDown() {
        serviceController?.destroy()
        serviceController = null
        MediaSourceStore.saveSelectedPackage(context, null)
        resetState()
    }

    @Test
    fun newMediaCancelsOldLookup_andOnlyLatestLyricsReachTextView() {
        saveLocalPlainLyrics(OLD_TITLE, "[00:01.00]old lyrics")
        saveLocalPlainLyrics(NEW_TITLE, "[00:01.00]new lyrics")
        saveLocalPlainLyrics(DESTROYED_TITLE, "[00:01.00]destroyed lyrics")

        val controller = Robolectric.buildService(QueuedLookupFloatingLyricsService::class.java)
            .create()
            .also { serviceController = it }
        val service = controller.get()
        val callbackDispatcher = service.callbackDispatcher

        assertTrue("Robolectric must expose a real floating TextView", service.showLyrics())
        val lyricsView = requireNotNull(service.lyricsView)

        assertTrue(service.applyCurrentMediaInfo(media(OLD_TITLE, sequence = 1L)))
        val oldDelivery = callbackDispatcher.takeDelivery()

        assertTrue(service.applyCurrentMediaInfo(media(NEW_TITLE, sequence = 2L)))
        val newDelivery = callbackDispatcher.takeDelivery()

        oldDelivery()
        newDelivery()

        assertEquals("new lyrics", lyricsView.text.toString())

        assertTrue(service.applyCurrentMediaInfo(media(DESTROYED_TITLE, sequence = 3L)))
        val deliveryAfterDestroy = callbackDispatcher.takeDelivery()
        val textAtDestroy = lyricsView.text.toString()

        controller.destroy()
        serviceController = null
        deliveryAfterDestroy()

        assertEquals(
            "A queued callback must not change the TextView after service destruction",
            textAtDestroy,
            lyricsView.text.toString()
        )
    }

    @Test
    fun rejectedLatestLookup_clearsServiceRequestStateAndSearchingUi() {
        val controller = Robolectric.buildService(RejectedLookupFloatingLyricsService::class.java)
            .create()
            .also { serviceController = it }
        val service = controller.get()

        assertTrue(service.showLyrics())
        val lyricsView = requireNotNull(service.lyricsView)
        assertTrue(
            service.applyCurrentMediaInfo(
                media(
                    title = REJECTED_TITLE,
                    sequence = 10L,
                    isPlaying = true
                )
            )
        )

        assertNull(service.activeLyricsLookupRequestKey)
        assertFalse(
            lyricsView.text.toString().contains(
                context.getString(R.string.ui_searching_lyrics)
            )
        )
        assertTrue(lyricsView.text.toString().contains(REJECTED_TITLE))
    }

    @Test
    fun lyricsChanged_visibleServiceReloadsSameSongAndIgnoresDifferentSong() {
        saveLocalPlainLyrics(OLD_TITLE, "[00:01.00]initial lyrics")
        val controller = Robolectric.buildService(QueuedLookupFloatingLyricsService::class.java)
            .create()
            .also { serviceController = it }
        val service = controller.get()
        val callbackDispatcher = service.callbackDispatcher
        assertTrue(service.showLyrics())
        val lyricsView = requireNotNull(service.lyricsView)
        assertTrue(service.applyCurrentMediaInfo(media(OLD_TITLE, sequence = 1L)))
        callbackDispatcher.takeDelivery().invoke()
        assertEquals("initial lyrics", lyricsView.text.toString())

        saveLocalPlainLyrics(OLD_TITLE, "[00:01.00]first changed lyrics")
        publishLyricsChanged(
            SongIdentity(
                title = OLD_TITLE.lowercase(),
                artist = "  $ARTIST ",
                album = "Different album is ignored by production matching",
                durationMs = DURATION_MS + 3_000L
            )
        )
        val firstChangedDelivery = callbackDispatcher.takeDelivery()

        saveLocalPlainLyrics(OLD_TITLE, "[00:01.00]latest changed lyrics")
        publishLyricsChanged(song(OLD_TITLE))
        val latestChangedDelivery = callbackDispatcher.takeDelivery()

        firstChangedDelivery()
        latestChangedDelivery()
        assertEquals("latest changed lyrics", lyricsView.text.toString())

        publishLyricsChanged(song(NEW_TITLE))
        service.awaitLookupIdle()
        assertEquals(0, callbackDispatcher.pendingDeliveryCount())

        controller.destroy()
        serviceController = null
        publishLyricsChanged(song(OLD_TITLE))
        assertEquals(0, callbackDispatcher.pendingDeliveryCount())
        assertNull(service.activeLyricsLookupRequestKey)
    }

    private fun saveLocalPlainLyrics(title: String, plainLrc: String) {
        assertTrue(
            LyricsStorage.savePlainLyrics(
                context = context,
                title = title,
                artist = ARTIST,
                duration = DURATION_MS,
                plainLrc = plainLrc,
                plainProvider = "service-test"
            )
        )
    }

    private fun media(
        title: String,
        sequence: Long,
        isPlaying: Boolean = false
    ): CurrentMediaInfo {
        return CurrentMediaInfo(
            sourcePackage = SOURCE_PACKAGE,
            title = title,
            artist = ARTIST,
            album = ALBUM,
            durationMs = DURATION_MS,
            isPlaying = isPlaying,
            positionMs = 1_000L,
            snapshotSequence = sequence
        )
    }

    private fun song(title: String): SongIdentity {
        return SongIdentity(
            title = title,
            artist = ARTIST,
            album = ALBUM,
            durationMs = DURATION_MS
        )
    }

    private fun publishLyricsChanged(target: SongIdentity) {
        BroadcastLyricsChangedPublisher(context).publish(target)
        ShadowLooper.idleMainLooper()
    }

    private fun resetState() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences("floating_quick_control", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences("floating_lyrics_style", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences("lyrics_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        File(base, FALLBACK_LYRICS_DIR).deleteRecursively()
    }

    class QueuedCallbackDispatcher : LyricsLookupCallbackDispatcher {
        private val deliveries = LinkedBlockingQueue<() -> Unit>()

        override fun dispatch(block: () -> Unit) {
            deliveries.put(block)
        }

        fun takeDelivery(): () -> Unit {
            return requireNotNull(deliveries.poll(5, TimeUnit.SECONDS)) {
                "Timed out waiting for a completed lookup to queue its callback"
            }
        }

        fun pendingDeliveryCount(): Int = deliveries.size
    }

    class QueuedLookupFloatingLyricsService : FloatingLyricsService() {
        val callbackDispatcher = QueuedCallbackDispatcher()
        private val lookupExecutor = Executors.newSingleThreadExecutor()

        override fun createLyricsLookupRunner(): LyricsLookupRunner {
            return LyricsLookupRunner(
                threadNamePrefix = "FloatingLyricsServiceLatestMediaTest",
                callbackDispatcher = callbackDispatcher,
                executor = lookupExecutor
            )
        }

        fun awaitLookupIdle() {
            val idle = java.util.concurrent.CountDownLatch(1)
            lookupExecutor.execute { idle.countDown() }
            assertTrue("Timed out waiting for lyrics lookup executor", idle.await(5, TimeUnit.SECONDS))
        }
    }

    class RejectedLookupFloatingLyricsService : FloatingLyricsService() {
        private val rejectedExecutor = createLyricsLookupExecutor(
            "FloatingLyricsRejectedLookup"
        ).apply {
            shutdown()
        }

        override fun createLyricsLookupRunner(): LyricsLookupRunner {
            return LyricsLookupRunner(
                threadNamePrefix = "unused-by-injected-executor",
                callbackDispatcher = { block -> block() },
                executor = rejectedExecutor
            )
        }
    }

    private companion object {
        const val SOURCE_PACKAGE = "player.service.test"
        const val ARTIST = "AndSi"
        const val ALBUM = "Service Test Album"
        const val DURATION_MS = 180_000L
        const val OLD_TITLE = "Old Service Song"
        const val NEW_TITLE = "New Service Song"
        const val DESTROYED_TITLE = "Destroyed Service Song"
        const val REJECTED_TITLE = "Rejected Service Song"
    }
}
