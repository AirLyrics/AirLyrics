package com.andsi.airlyrics.floating

import android.app.Application
import android.app.Service
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.SongIdentity
import com.andsi.airlyrics.lyrics.LyricsChangedBroadcast
import com.andsi.airlyrics.lyrics.LyricsLookupCallbackDispatcher
import com.andsi.airlyrics.lyrics.LyricsLookupRunner
import com.andsi.airlyrics.lyrics.storage.FALLBACK_LYRICS_DIR
import com.andsi.airlyrics.lyrics.storage.LyricsStorage
import com.andsi.airlyrics.lyrics.storage.PREFS_NAME
import com.andsi.airlyrics.media.CurrentMediaBroadcast
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class FloatingLyricsServiceCommandLifecycleTest {
    private lateinit var application: Application
    private var serviceController: ServiceController<out FloatingLyricsService>? = null

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        resetState()
        ShadowSettings.setCanDrawOverlays(true)
    }

    @After
    fun tearDown() {
        serviceController?.destroy()
        serviceController = null
        MediaSourceStore.saveSelectedPackage(application, null)
        resetState()
    }

    @Test
    fun serviceCommands_showAndHide_updateActualFloatingWindowState() {
        val controller = Robolectric.buildService(FloatingLyricsService::class.java)
            .create()
            .also { serviceController = it }
        val service = controller.get()
        val windowManager = shadowWindowManager(service)

        assertEquals(Service.START_STICKY, send(service, FloatingServiceCommand.Show, startId = 1))
        val firstView = requireNotNull(service.lyricsView)
        assertTrue(service.windowController.isVisible)
        assertTrue(QuickFloatingStore.isDesiredVisible(service))
        assertEquals(1, windowManager.views.count { it === firstView })

        send(service, FloatingServiceCommand.Show, startId = 2)

        assertSame(firstView, service.lyricsView)
        assertEquals(
            "Repeated Show must reuse the production floating view",
            1,
            windowManager.views.count { it === firstView }
        )

        send(service, FloatingServiceCommand.Hide, startId = 3)

        assertFalse(service.windowController.isVisible)
        assertFalse(QuickFloatingStore.isDesiredVisible(service))
        assertNull(service.lyricsView)
        assertFalse(windowManager.views.any { it === firstView })

        send(service, FloatingServiceCommand.Hide, startId = 4)

        assertFalse(service.windowController.isVisible)
        assertNull(service.lyricsView)
        assertFalse(windowManager.views.any { it === firstView })
    }

    @Test
    fun serviceCommands_lockAndClickThrough_updateWindowFlagsAndState() {
        val controller = Robolectric.buildService(FloatingLyricsService::class.java)
            .create()
            .also { serviceController = it }
        val service = controller.get()
        val windowManager = shadowWindowManager(service)
        send(service, FloatingServiceCommand.Show)
        val view = requireNotNull(service.lyricsView)
        val layoutParams = view.layoutParams as WindowManager.LayoutParams

        assertTrue(layoutParams.hasFlag(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE))
        assertFalse(layoutParams.hasFlag(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE))

        // Once click-through is explicitly configured, lock and click-through are independent.
        send(service, FloatingServiceCommand.ClickThroughOff)
        send(service, FloatingServiceCommand.Lock)

        assertTrue(FloatingLyricsStyleStore.isLocked(service))
        assertFalse(
            "Lock prevents dragging; it must not silently become click-through",
            layoutParams.hasFlag(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        )
        val lockedX = layoutParams.x
        drag(view)
        assertEquals("A locked overlay must not move", lockedX, layoutParams.x)
        assertEquals(
            FloatingWindowStateBroadcast.State(
                visible = true,
                locked = true,
                clickThrough = false
            ),
            latestWindowState()
        )

        send(service, FloatingServiceCommand.Unlock)
        drag(view)
        assertEquals("An unlocked overlay remains draggable", lockedX + 100, layoutParams.x)

        send(service, FloatingServiceCommand.ClickThroughOn)

        assertTrue(FloatingLyricsStyleStore.isClickThrough(service))
        assertTrue(layoutParams.hasFlag(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE))
        assertTrue(layoutParams.hasFlag(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE))
        assertEquals(
            FloatingWindowStateBroadcast.State(
                visible = true,
                locked = false,
                clickThrough = true
            ),
            latestWindowState()
        )

        send(service, FloatingServiceCommand.ClickThroughOff)

        assertFalse(FloatingLyricsStyleStore.isClickThrough(service))
        assertFalse(layoutParams.hasFlag(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE))

        send(service, FloatingServiceCommand.Hide)
        val detachedFlags = layoutParams.flags
        send(service, FloatingServiceCommand.ClickThroughOn)

        assertNull(service.lyricsView)
        assertFalse(windowManager.views.any { it === view })
        assertEquals(
            "A post-hide command must not mutate the removed view",
            detachedFlags,
            layoutParams.flags
        )
        assertEquals(
            FloatingWindowStateBroadcast.State(
                visible = false,
                locked = false,
                clickThrough = true
            ),
            latestWindowState()
        )
    }

    @Test
    fun reloadLyricsCommand_usesCurrentMediaAndUpdatesActualTextView() {
        MediaSourceStore.saveSelectedPackage(application, SOURCE_PACKAGE)
        val controller = Robolectric.buildService(QueuedLookupFloatingLyricsService::class.java)
            .create()
            .also { serviceController = it }
        val service = controller.get()
        send(service, FloatingServiceCommand.Show)
        val lyricsView = requireNotNull(service.lyricsView)

        send(service, FloatingServiceCommand.ReloadLyrics)

        assertTrue(service.currentMedia.isEmpty)
        assertNull(service.activeLyricsLookupRequestKey)
        assertEquals(0, service.callbackDispatcher.pendingDeliveryCount())

        saveLocalPlainLyrics("[00:01.00]initial command lyrics")
        application.sendBroadcast(CurrentMediaBroadcast.mediaUpdateIntent(application, media()))
        ShadowLooper.idleMainLooper()
        service.callbackDispatcher.takeDelivery().invoke()
        assertEquals("initial command lyrics", lyricsView.text.toString())

        saveLocalPlainLyrics("[00:01.00]reloaded command lyrics")
        send(service, FloatingServiceCommand.ReloadLyrics)
        assertNotNull(
            "ReloadLyrics must enter the existing lookup runner",
            service.activeLyricsLookupRequestKey
        )
        service.callbackDispatcher.takeDelivery().invoke()

        assertEquals("reloaded command lyrics", lyricsView.text.toString())
        assertNull(service.activeLyricsLookupRequestKey)
    }

    @Test
    fun destroy_cleansWindowReceiversAndPendingCallbacks() {
        MediaSourceStore.saveSelectedPackage(application, SOURCE_PACKAGE)
        FloatingLyricsStyleStore.setAutoHideWhenPaused(application, true)
        saveLocalPlainLyrics("[00:01.00]must not render after destroy")
        val mediaIntent = CurrentMediaBroadcast.mediaUpdateIntent(
            application,
            media(isPlaying = true)
        )
        val lyricsChangedIntent = requireNotNull(
            LyricsChangedBroadcast.lyricsChangedIntent(application, song())
        )
        val mediaReceiverCountBefore = receiverCount(mediaIntent.action)
        val lyricsReceiverCountBefore = receiverCount(lyricsChangedIntent.action)
        val controller = Robolectric.buildService(QueuedLookupFloatingLyricsService::class.java)
            .create()
            .also { serviceController = it }
        val service = controller.get()

        assertEquals(mediaReceiverCountBefore + 1, receiverCount(mediaIntent.action))
        assertEquals(lyricsReceiverCountBefore + 1, receiverCount(lyricsChangedIntent.action))

        send(service, FloatingServiceCommand.Show)
        val lyricsView = requireNotNull(service.lyricsView)
        val windowManager = shadowWindowManager(service)
        application.sendBroadcast(mediaIntent)
        ShadowLooper.idleMainLooper()
        val queuedDelivery = service.callbackDispatcher.takeDelivery()
        application.sendBroadcast(
            CurrentMediaBroadcast.mediaUpdateIntent(
                application,
                media(sequence = 2L, isPlaying = false)
            )
        )
        ShadowLooper.idleMainLooper()
        val textAtDestroy = lyricsView.text.toString()

        assertTrue(windowManager.views.any { it === lyricsView })
        assertNotNull(service.activeLyricsLookupRequestKey)
        assertTrue(service.syncHandler.hasCallbacks(service.syncRunnable))
        assertTrue(service.syncHandler.hasCallbacks(service.pauseAutoHideRunnable))
        assertTrue(service.syncHandler.hasCallbacks(service.currentMediaRefreshRunnable))

        controller.destroy()
        serviceController = null

        assertFalse(service.windowController.isVisible)
        assertNull(service.lyricsView)
        assertFalse(windowManager.views.any { it === lyricsView })
        assertNull(service.activeLyricsLookupRequestKey)
        assertFalse(service.syncHandler.hasCallbacks(service.syncRunnable))
        assertFalse(service.syncHandler.hasCallbacks(service.pauseAutoHideRunnable))
        assertFalse(service.syncHandler.hasCallbacks(service.mediaRestoreRunnable))
        assertFalse(service.syncHandler.hasCallbacks(service.currentMediaRefreshRunnable))
        assertEquals(mediaReceiverCountBefore, receiverCount(mediaIntent.action))
        assertEquals(lyricsReceiverCountBefore, receiverCount(lyricsChangedIntent.action))

        queuedDelivery()
        application.sendBroadcast(
            CurrentMediaBroadcast.mediaUpdateIntent(
                application,
                media(title = "Post-destroy media", sequence = 2L)
            )
        )
        application.sendBroadcast(lyricsChangedIntent)
        ShadowLooper.idleMainLooper()

        assertEquals(textAtDestroy, lyricsView.text.toString())
        assertEquals(0, service.callbackDispatcher.pendingDeliveryCount())
        assertEquals(TITLE, service.currentMedia.title)

        service.onDestroy()
        assertFalse(service.windowController.isVisible)
        assertNull(service.lyricsView)
    }

    private fun send(
        service: FloatingLyricsService,
        command: FloatingServiceCommand,
        startId: Int = 1
    ): Int {
        return service.onStartCommand(command.toIntent(application), 0, startId)
    }

    private fun shadowWindowManager(service: FloatingLyricsService): ShadowWindowManagerImpl {
        val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return shadowOf(windowManager) as ShadowWindowManagerImpl
    }

    private fun latestWindowState(): FloatingWindowStateBroadcast.State? {
        return shadowOf(application).broadcastIntents
            .asReversed()
            .firstNotNullOfOrNull(FloatingWindowStateBroadcast::readState)
    }

    private fun receiverCount(action: String?): Int {
        return shadowOf(application).registeredReceivers.count { wrapper ->
            action != null && wrapper.intentFilter.hasAction(action)
        }
    }

    private fun drag(view: View) {
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 10f, 20f, 0)
        val move = MotionEvent.obtain(0L, 10L, MotionEvent.ACTION_MOVE, 110f, 20f, 0)
        val up = MotionEvent.obtain(0L, 20L, MotionEvent.ACTION_UP, 110f, 20f, 0)
        try {
            assertTrue(view.dispatchTouchEvent(down))
            assertTrue(view.dispatchTouchEvent(move))
            assertTrue(view.dispatchTouchEvent(up))
        } finally {
            down.recycle()
            move.recycle()
            up.recycle()
        }
    }

    private fun saveLocalPlainLyrics(plainLrc: String) {
        assertTrue(
            LyricsStorage.savePlainLyrics(
                context = application,
                title = TITLE,
                artist = ARTIST,
                duration = DURATION_MS,
                plainLrc = plainLrc,
                plainProvider = "service-command-test"
            )
        )
    }

    private fun media(
        title: String = TITLE,
        sequence: Long = 1L,
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

    private fun song(): SongIdentity {
        return SongIdentity(
            title = TITLE,
            artist = ARTIST,
            album = ALBUM,
            durationMs = DURATION_MS
        )
    }

    private fun resetState() {
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        application.getSharedPreferences("floating_quick_control", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        application.getSharedPreferences("floating_lyrics_style", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        application.getSharedPreferences("lyrics_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val base = application.getExternalFilesDir(null) ?: application.filesDir
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
                threadNamePrefix = "FloatingLyricsServiceCommandLifecycleTest",
                callbackDispatcher = callbackDispatcher,
                executor = lookupExecutor
            )
        }
    }

    private fun WindowManager.LayoutParams.hasFlag(flag: Int): Boolean {
        return flags and flag != 0
    }

    private companion object {
        const val SOURCE_PACKAGE = "player.command.lifecycle.test"
        const val TITLE = "Command Lifecycle Song"
        const val ARTIST = "AndSi"
        const val ALBUM = "Command Lifecycle Album"
        const val DURATION_MS = 180_000L
    }
}
