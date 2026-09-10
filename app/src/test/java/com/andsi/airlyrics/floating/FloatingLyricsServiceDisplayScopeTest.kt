package com.andsi.airlyrics.floating

import android.app.AppOpsManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.R
import com.andsi.airlyrics.displayscope.DisplayScopeBlockReason
import com.andsi.airlyrics.displayscope.DisplayScopeVisibilitySnapshot
import com.andsi.airlyrics.settings.store.DisplayScopeStore
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
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
        setUsageAccess(granted = false)
    }

    @After
    fun tearDown() {
        controller?.destroy()
        controller = null
        reset()
        ShadowSettings.setCanDrawOverlays(false)
        setUsageAccess(granted = false)
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
        assertLatestNotificationStartsWith(service, R.string.ui_display_blocked)

        FloatingLyricsStyleStore.setAutoHideWhenPaused(service, true)
        service.autoHiddenForPause = true
        service.showLyrics()
        assertTrue(service.pauseAutoHideSuppressedByUser)

        setUsageAccess(granted = true)
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

    @Test
    fun startupReportsBlockedUntilSelectedAppBecomesVisible() {
        setUsageAccess(granted = true)
        DisplayScopeStore.setSelectedPackages(application, setOf("player.app"))
        DisplayScopeStore.setEnabled(application, true)
        QuickFloatingStore.setDesiredVisible(application, true)
        val service = Robolectric.buildService(FloatingLyricsService::class.java)
            .create()
            .also { controller = it }
            .get()
        service.displayScopeMonitor?.close()
        service.displayScopeMonitor = null

        assertEquals(
            DisplayScopeBlockReason.CHECKING_SELECTED_APPS,
            service.displayScopeBlockReason
        )
        assertNotificationStartsWith(
            service,
            shadowOf(service).lastForegroundNotification,
            R.string.ui_display_blocked
        )

        service.onStartCommand(FloatingServiceCommand.Restore.toIntent(service), 0, 1)

        assertFalse(service.windowController.isVisible)
        assertTrue(service.autoHiddenForDisplayScope)
        assertEquals(
            DisplayScopeBlockReason.CHECKING_SELECTED_APPS,
            service.displayScopeBlockReason
        )
        assertLatestNotificationStartsWith(service, R.string.ui_display_blocked)

        service.applyDisplayScopeSnapshot(
            DisplayScopeVisibilitySnapshot(
                usageAccessGranted = true,
                visiblePackages = emptySet(),
                visibilitySnapshotAvailable = false
            )
        )

        assertEquals(
            DisplayScopeBlockReason.CHECKING_SELECTED_APPS,
            service.displayScopeBlockReason
        )

        service.applyDisplayScopeSnapshot(
            DisplayScopeVisibilitySnapshot(
                usageAccessGranted = true,
                visiblePackages = setOf("launcher.app")
            )
        )

        assertFalse(service.windowController.isVisible)
        assertEquals(
            DisplayScopeBlockReason.WAITING_FOR_SELECTED_APP,
            service.displayScopeBlockReason
        )
        assertLatestNotificationStartsWith(service, R.string.ui_display_blocked)

        service.applyDisplayScopeSnapshot(
            DisplayScopeVisibilitySnapshot(
                usageAccessGranted = true,
                visiblePackages = setOf("player.app")
            )
        )

        assertTrue(service.windowController.isVisible)
        assertNull(service.displayScopeBlockReason)
        assertLatestNotificationStartsWith(service, R.string.ui_shown)
    }

    @Test
    fun coldDisplayScopeDisableRestoresDesiredWindow() {
        QuickFloatingStore.setDesiredVisible(application, true)
        val service = Robolectric.buildService(FloatingLyricsService::class.java)
            .create()
            .also { controller = it }
            .get()
        service.displayScopeMonitor?.close()
        service.displayScopeMonitor = null

        service.onStartCommand(FloatingServiceCommand.ApplyDisplayScope.toIntent(service), 0, 1)

        assertTrue(service.windowController.isVisible)
        assertTrue(QuickFloatingStore.isDesiredVisible(service))
        assertLatestNotificationStartsWith(service, R.string.ui_shown)
    }

    private fun assertLatestNotificationStartsWith(
        service: FloatingLyricsService,
        statusRes: Int
    ) {
        val manager = application.getSystemService(NotificationManager::class.java)
        val notification = requireNotNull(
            shadowOf(manager).getNotification(FloatingServiceNotification.NOTIFICATION_ID)
        )
        assertNotificationStartsWith(service, notification, statusRes)
    }

    private fun assertNotificationStartsWith(
        service: FloatingLyricsService,
        notification: Notification,
        statusRes: Int
    ) {
        val contentText = notification.extras
            .getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()
            .orEmpty()
        assertTrue(contentText.startsWith(service.getString(statusRes)))
    }

    private fun setUsageAccess(granted: Boolean) {
        val appOps = application.getSystemService(AppOpsManager::class.java)
        shadowOf(appOps).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            application.packageName,
            if (granted) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_IGNORED
        )
    }

    private fun reset() {
        DisplayScopeStore.setEnabled(application, false)
        DisplayScopeStore.setSelectedPackages(application, emptySet())
        FloatingLyricsStyleStore.setAutoHideWhenPaused(application, false)
        QuickFloatingStore.setDesiredVisible(application, false)
    }
}
