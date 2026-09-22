package com.andsi.airlyrics.floating

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class FloatingLyricsServiceAutoHideTest {
    private lateinit var application: Application
    private var controller: ServiceController<FloatingLyricsService>? = null

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        resetState()
        ShadowSettings.setCanDrawOverlays(true)
    }

    @After
    fun tearDown() {
        controller?.destroy()
        controller = null
        resetState()
        ShadowSettings.setCanDrawOverlays(false)
    }

    @Test
    fun unavailableLyrics_showStatusThenHideAndRestoreWhenLyricsArrive() {
        val service = createVisibleService()
        val media = media(isPlaying = true)
        service.currentMedia = media
        FloatingLyricsStyleStore.setAutoHideWhenLyricsUnavailable(service, true)
        val expectedFailureText = service.lookupFailureText(null, media)

        service.applyLyricsResult(Result.success(null), media)

        assertEquals(LyricsAvailability.UNAVAILABLE, service.lyricsAvailability)
        assertTrue(service.unavailableLyricsAutoHidePending)
        assertTrue(service.windowController.isVisible)
        assertEquals(expectedFailureText, requireNotNull(service.lyricsView).text.toString())

        ShadowLooper.idleMainLooper(
            AUTO_HIDE_WHEN_LYRICS_UNAVAILABLE_DELAY_MS - 1L,
            TimeUnit.MILLISECONDS
        )
        assertTrue(service.windowController.isVisible)

        ShadowLooper.idleMainLooper(1L, TimeUnit.MILLISECONDS)
        assertFalse(service.windowController.isVisible)
        assertTrue(QuickFloatingStore.isDesiredVisible(service))
        assertTrue(AutoHideReason.LYRICS_UNAVAILABLE in service.activeAutoHideReasons)

        service.applyLyricsResult(
            Result.success(
                LyricsProviderResult(
                    plainProviderId = "test",
                    plainProviderName = "Test",
                    plainLrc = "[00:01.00]available lyrics"
                )
            ),
            media
        )

        assertEquals(LyricsAvailability.AVAILABLE, service.lyricsAvailability)
        assertTrue(service.windowController.isVisible)
        assertEquals("available lyrics", requireNotNull(service.lyricsView).text.toString())
    }

    @Test
    fun manualShow_suppressesUnavailableLyricsUntilTheSongChanges() {
        val service = createVisibleService()
        val firstMedia = media(title = "First", isPlaying = true)
        service.currentMedia = firstMedia
        FloatingLyricsStyleStore.setAutoHideWhenLyricsUnavailable(service, true)
        val expectedFailureText = service.lookupFailureText(null, firstMedia)
        service.applyLyricsResult(Result.success(null), firstMedia)
        ShadowLooper.idleMainLooper(
            AUTO_HIDE_WHEN_LYRICS_UNAVAILABLE_DELAY_MS,
            TimeUnit.MILLISECONDS
        )
        assertFalse(service.windowController.isVisible)

        assertTrue(service.showLyrics())
        assertTrue(service.windowController.isVisible)
        assertEquals(firstMedia.playbackLyricsKey(), service.lyricsUnavailableAutoHideSuppressedKey)
        assertEquals(expectedFailureText, requireNotNull(service.lyricsView).text.toString())

        service.applyLyricsResult(Result.success(null), firstMedia)
        ShadowLooper.idleMainLooper(
            AUTO_HIDE_WHEN_LYRICS_UNAVAILABLE_DELAY_MS,
            TimeUnit.MILLISECONDS
        )
        assertTrue(service.windowController.isVisible)

        val nextMedia = media(title = "Next", isPlaying = true)
        service.currentMedia = nextMedia
        service.markLyricsLoading(nextMedia.playbackLyricsKey())
        service.applyLyricsResult(Result.success(null), nextMedia)
        ShadowLooper.idleMainLooper(
            AUTO_HIDE_WHEN_LYRICS_UNAVAILABLE_DELAY_MS,
            TimeUnit.MILLISECONDS
        )

        assertFalse(service.windowController.isVisible)
        assertEquals(null, service.lyricsUnavailableAutoHideSuppressedKey)
    }

    @Test
    fun overlappingReasons_restoreOnlyAfterEveryReasonClears() {
        val service = createVisibleService()
        val media = media(isPlaying = false)
        service.currentMedia = media
        FloatingLyricsStyleStore.setAutoHideWhenPaused(service, true)
        FloatingLyricsStyleStore.setAutoHideWhenLyricsUnavailable(service, true)

        service.applyLyricsResult(Result.success(null), media)
        ShadowLooper.idleMainLooper(AUTO_HIDE_WHEN_PAUSED_DELAY_MS, TimeUnit.MILLISECONDS)

        assertFalse(service.windowController.isVisible)
        assertTrue(AutoHideReason.PAUSED in service.activeAutoHideReasons)

        ShadowLooper.idleMainLooper(
            AUTO_HIDE_WHEN_LYRICS_UNAVAILABLE_DELAY_MS - AUTO_HIDE_WHEN_PAUSED_DELAY_MS,
            TimeUnit.MILLISECONDS
        )
        assertTrue(AutoHideReason.LYRICS_UNAVAILABLE in service.activeAutoHideReasons)

        service.currentMedia = media.copy(isPlaying = true, snapshotSequence = 2L)
        service.reevaluateAutoHide()

        assertFalse(AutoHideReason.PAUSED in service.activeAutoHideReasons)
        assertTrue(AutoHideReason.LYRICS_UNAVAILABLE in service.activeAutoHideReasons)
        assertFalse(service.windowController.isVisible)

        service.markLyricsAvailable(service.currentMedia.playbackLyricsKey())

        assertTrue(service.activeAutoHideReasons.isEmpty())
        assertTrue(service.windowController.isVisible)
    }

    @Test
    fun settingChanges_reevaluateTheCurrentUnavailableState() {
        val service = createVisibleService()
        val media = media(isPlaying = true)
        service.currentMedia = media
        service.applyLyricsResult(Result.success(null), media)

        assertFalse(service.unavailableLyricsAutoHidePending)
        assertTrue(service.windowController.isVisible)

        FloatingLyricsStyleStore.setAutoHideWhenLyricsUnavailable(service, true)
        service.applyAutoHideSettings()
        assertTrue(service.unavailableLyricsAutoHidePending)

        ShadowLooper.idleMainLooper(
            AUTO_HIDE_WHEN_LYRICS_UNAVAILABLE_DELAY_MS,
            TimeUnit.MILLISECONDS
        )
        assertFalse(service.windowController.isVisible)

        FloatingLyricsStyleStore.setAutoHideWhenLyricsUnavailable(service, false)
        service.applyAutoHideSettings()

        assertTrue(service.activeAutoHideReasons.isEmpty())
        assertTrue(service.windowController.isVisible)
    }

    @Test
    fun lookupFailureAndParsedEmptyPayload_areUnavailableAndDestroyCancelsTimer() {
        val service = createVisibleService()
        val media = media(isPlaying = true)
        service.currentMedia = media
        FloatingLyricsStyleStore.setAutoHideWhenLyricsUnavailable(service, true)

        service.applyLyricsResult(Result.failure(IllegalStateException("lookup failed")), media)

        assertEquals(LyricsAvailability.UNAVAILABLE, service.lyricsAvailability)
        assertTrue(service.syncHandler.hasCallbacks(service.unavailableLyricsAutoHideRunnable))

        service.resetLyricsAvailability()

        service.applyLyricsResult(
            Result.success(
                LyricsProviderResult(
                    plainProviderId = "test",
                    plainProviderName = "Test",
                    plainLrc = "[ar:Metadata only]"
                )
            ),
            media
        )

        assertEquals(LyricsAvailability.UNAVAILABLE, service.lyricsAvailability)
        assertTrue(service.syncHandler.hasCallbacks(service.unavailableLyricsAutoHideRunnable))

        controller?.destroy()
        controller = null

        assertFalse(service.syncHandler.hasCallbacks(service.unavailableLyricsAutoHideRunnable))
    }

    private fun createVisibleService(): FloatingLyricsService {
        return Robolectric.buildService(FloatingLyricsService::class.java)
            .create()
            .also { controller = it }
            .get()
            .also { service -> assertTrue(service.showLyrics()) }
    }

    private fun media(
        title: String = "Song",
        isPlaying: Boolean
    ): CurrentMediaInfo {
        return CurrentMediaInfo(
            sourcePackage = "player.app",
            title = title,
            artist = "Artist",
            album = "Album",
            durationMs = 180_000L,
            isPlaying = isPlaying,
            positionMs = 1_000L,
            snapshotSequence = 1L
        )
    }

    private fun resetState() {
        QuickFloatingStore.setDesiredVisible(application, false)
        application.getSharedPreferences("floating_lyrics_style", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
