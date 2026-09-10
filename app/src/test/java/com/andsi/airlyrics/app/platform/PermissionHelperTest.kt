package com.andsi.airlyrics.app.platform

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(RobolectricTestRunner::class)
class PermissionHelperTest {
    @After
    fun tearDown() {
        ShadowNotificationManager.reset()
    }

    @Test
    @Config(sdk = [28])
    fun notificationState_reflectsSystemSettingBeforeRuntimePermissionExists() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notifications = context.getSystemService(NotificationManager::class.java)
        val shadowNotifications = shadowOf(notifications)

        shadowNotifications.setNotificationsEnabled(false)
        assertFalse(PermissionHelper.hasPostNotificationsPermission(context))

        shadowNotifications.setNotificationsEnabled(true)
        assertTrue(PermissionHelper.hasPostNotificationsPermission(context))
    }

    @Test
    @Config(sdk = [33])
    fun notificationState_requiresRuntimePermissionAndEnabledSystemSetting() {
        val application = RuntimeEnvironment.getApplication()
        val notifications = application.getSystemService(NotificationManager::class.java)
        val shadowNotifications = shadowOf(notifications)
        val shadowApplication = shadowOf(application)

        shadowNotifications.setNotificationsEnabled(true)
        shadowApplication.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(PermissionHelper.hasPostNotificationsPermission(application))

        shadowApplication.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(PermissionHelper.hasPostNotificationsPermission(application))

        shadowNotifications.setNotificationsEnabled(false)
        assertFalse(PermissionHelper.hasPostNotificationsPermission(application))
    }
}
