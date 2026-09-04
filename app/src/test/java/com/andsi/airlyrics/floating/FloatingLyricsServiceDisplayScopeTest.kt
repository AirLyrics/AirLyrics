package com.andsi.airlyrics.floating

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.displayscope.DisplayScopeBlockReason
import com.andsi.airlyrics.displayscope.DisplayScopeVisibilitySnapshot
import com.andsi.airlyrics.settings.store.DisplayScopeStore
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore
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
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
class FloatingLyricsServiceDisplayScopeTest {
    private lateinit var application: Application
    private var controller: ServiceController<FloatingLyricsService>? = null

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        reset()
        ShadowSettings.setCanDrawOverlays(true)
    }

    @After
    fun tearDown() {
        controller?.destroy()
        controller = null
        reset()
        ShadowSettings.setCanDrawOverlays(false)
    }

    @Test
    fun filterTemporarilyHidesAndRestoresWithoutClearingUserIntent() {
        DisplayScopeStore.setSelectedPackages(application, setOf("player.app"))
        DisplayScopeStore.setEnabled(application, true)
        val service = Robolectric.buildService(FloatingLyricsService::class.java)
            .create()
            .also { controller = it }
            .get()
        service.displayScopeMonitor?.close()
        service.displayScopeMonitor = null

        service.onStartCommand(FloatingServiceCommand.Show.toIntent(service), 0, 1)

        assertFalse(service.windowController.isVisible)
        assertTrue(QuickFloatingStore.isDesiredVisible(service))
        assertEquals(DisplayScopeBlockReason.USAGE_ACCESS_REQUIRED, service.displayScopeBlockReason)

        FloatingLyricsStyleStore.setAutoHideWhenPaused(service, true)
        service.autoHiddenForPause = true
        service.showLyrics()
        assertTrue(service.pauseAutoHideSuppressedByUser)

        service.applyDisplayScopeSnapshot(
            DisplayScopeVisibilitySnapshot(
                usageAccessGranted = true,
                visiblePackages = setOf("player.app")
            )
        )
        assertTrue(service.windowController.isVisible)
        assertFalse(service.autoHiddenForPause)

        service.applyDisplayScopeSnapshot(
            DisplayScopeVisibilitySnapshot(
                usageAccessGranted = true,
                visiblePackages = setOf("launcher.app")
            )
        )
        assertFalse(service.windowController.isVisible)
        assertTrue(QuickFloatingStore.isDesiredVisible(service))
        assertEquals(DisplayScopeBlockReason.WAITING_FOR_SELECTED_APP, service.displayScopeBlockReason)

        DisplayScopeStore.setEnabled(service, false)
        service.applyDisplayScopeSetting()
        assertTrue(service.windowController.isVisible)
        assertTrue(QuickFloatingStore.isDesiredVisible(service))
    }

    private fun reset() {
        DisplayScopeStore.setEnabled(application, false)
        DisplayScopeStore.setSelectedPackages(application, emptySet())
        FloatingLyricsStyleStore.setAutoHideWhenPaused(application, false)
        QuickFloatingStore.setDesiredVisible(application, false)
    }
}
