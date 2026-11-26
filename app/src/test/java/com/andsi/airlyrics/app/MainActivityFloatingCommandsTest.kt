package com.andsi.airlyrics.app

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.app.controller.FloatingVisibilityOutcome
import com.andsi.airlyrics.floating.FloatingLyricsService
import com.andsi.airlyrics.floating.FloatingServiceCommand
import com.andsi.airlyrics.floating.FloatingWindowStateBroadcast
import com.andsi.airlyrics.media.MediaSourceStore
import com.andsi.airlyrics.settings.store.QuickFloatingStore
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityFloatingCommandsTest {
    private lateinit var application: Application
    private var serviceController: ServiceController<FloatingLyricsService>? = null
    private var startId = 0

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        QuickFloatingStore.setDesiredVisible(application, false)
        MediaSourceStore.saveSelectedPackage(application, null)
        ShadowSettings.setCanDrawOverlays(true)
    }

    @After
    fun tearDown() {
        serviceController?.destroy()
        QuickFloatingStore.setDesiredVisible(application, false)
        MediaSourceStore.saveSelectedPackage(application, null)
        ShadowSettings.setCanDrawOverlays(false)
    }

    @Test
    fun reorderedShowAndHideUseLatestIntentAndStayClosedAfterRecreation() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { activityController ->
            val graph = activityController.get().graph
            var service = createService()

            assertEquals(FloatingVisibilityOutcome.SUCCESS, graph.floatingController.showLyrics())
            val oldShow = nextServiceCommand()
            assertEquals(FloatingVisibilityOutcome.SUCCESS, graph.floatingController.hideLyrics())
            deliver(service, nextServiceCommand())
            deliver(service, oldShow)

            assertFalse(QuickFloatingStore.isDesiredVisible(application))
            assertFalse(service.windowController.isVisible)
            assertFalse(graph.state.quickFloatingVisible)

            assertEquals(FloatingVisibilityOutcome.SUCCESS, graph.floatingController.hideLyrics())
            val oldHide = nextServiceCommand()
            assertEquals(FloatingVisibilityOutcome.SUCCESS, graph.floatingController.showLyrics())
            deliver(service, nextServiceCommand())
            deliver(service, oldHide)

            assertTrue(QuickFloatingStore.isDesiredVisible(application))
            assertTrue(service.windowController.isVisible)
            assertTrue(graph.state.quickFloatingVisible)

            assertEquals(FloatingVisibilityOutcome.SUCCESS, graph.floatingController.hideLyrics())
            deliver(service, nextServiceCommand())
            serviceController?.destroy()
            service = createService()
            deliver(service, FloatingServiceCommand.Restore.toIntent(application))

            assertFalse(graph.state.quickFloatingDesiredVisible)
            assertFalse(graph.state.quickFloatingVisible)
            assertFalse(service.windowController.isVisible)
        }
    }

    @Test
    fun delayedWindowBroadcastCannotReplaceNewerServiceSnapshot() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { activityController ->
            val graph = activityController.get().graph
            val service = createService()
            assertEquals(FloatingVisibilityOutcome.SUCCESS, graph.floatingController.showLyrics())
            deliver(service, nextServiceCommand())
            assertTrue(graph.state.quickFloatingVisible)
            val oldReceipt = shadowOf(application).broadcastIntents.last {
                FloatingWindowStateBroadcast.readState(it)?.visible == true
            }

            assertEquals(FloatingVisibilityOutcome.SUCCESS, graph.floatingController.hideLyrics())
            deliver(service, nextServiceCommand())
            application.sendBroadcast(oldReceipt)
            ShadowLooper.idleMainLooper()

            assertFalse(service.windowController.isVisible)
            assertFalse(graph.state.quickFloatingVisible)
        }
    }

    @Test
    fun reorderedMediaSourceCommandsUseLatestSelectionAndCanClearIt() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { activityController ->
            val graph = activityController.get().graph
            val service = createService()
            assertEquals(FloatingVisibilityOutcome.SUCCESS, graph.floatingController.showLyrics())
            deliver(service, nextServiceCommand())
            graph.mediaSourceController.selectSource("player.first")
            val oldSelection = nextServiceCommand()
            graph.mediaSourceController.selectSource("player.latest")
            deliver(service, nextServiceCommand())
            deliver(service, oldSelection)

            assertEquals("player.latest", MediaSourceStore.getSelectedPackage(application))
            assertEquals("player.latest", service.selectedSourcePackage)

            graph.mediaSourceController.selectSource(null)
            deliver(service, nextServiceCommand())

            assertNull(MediaSourceStore.getSelectedPackage(application))
            assertNull(service.selectedSourcePackage)
        }
    }

    @Test
    fun notificationShowRemainsRequestedWhileWaitingForPermission() {
        val service = createService()
        ShadowSettings.setCanDrawOverlays(false)
        deliver(service, FloatingServiceCommand.ToggleVisibleFromNotification.toIntent(application))

        assertTrue(QuickFloatingStore.isDesiredVisible(application))
        assertFalse(service.windowController.isVisible)

        ShadowSettings.setCanDrawOverlays(true)
        deliver(service, FloatingServiceCommand.Restore.toIntent(application))

        assertTrue(service.windowController.isVisible)
    }

    private fun createService(): FloatingLyricsService {
        return Robolectric.buildService(FloatingLyricsService::class.java).create()
            .also { serviceController = it }
            .get()
    }

    private fun nextServiceCommand(): Intent = requireNotNull(shadowOf(application).nextStartedService)

    private fun deliver(service: FloatingLyricsService, intent: Intent) {
        service.onStartCommand(intent, 0, ++startId)
        ShadowLooper.idleMainLooper()
    }
}
