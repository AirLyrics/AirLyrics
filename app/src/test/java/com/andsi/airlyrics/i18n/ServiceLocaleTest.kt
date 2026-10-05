package com.andsi.airlyrics.i18n

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.res.Configuration
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.R
import com.andsi.airlyrics.feedback.ToastAirFeedback
import com.andsi.airlyrics.floating.FloatingLyricsService
import com.andsi.airlyrics.floating.FloatingServiceNotification
import com.andsi.airlyrics.floating.selectMediaSource
import com.andsi.airlyrics.media.MediaNotificationListenerService
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 32], qualifiers = "en-rUS")
class ServiceLocaleTest {
    private val application: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun grantInternalBroadcastPermission() {
        shadowOf(application).grantPermissions("com.andsi.airlyrics.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }

    @Test
    fun coldServiceStart_restoresTraditionalChinese_withoutChangingSharedResources() {
        application.getSharedPreferences("airlyrics_language_settings", 0).edit()
            .putString("language_mode", "zh-TW").commit()
        LanguageSettingsStore.initialize(application)
        val originalConfiguration = Configuration(application.resources.configuration)
        val originalLocale = Locale.getDefault()
        val floating = Robolectric.buildService(FloatingLyricsService::class.java).create()
        val media = Robolectric.buildService(MediaNotificationListenerService::class.java).create()
        try {
            assertEquals("顯示", floating.get().getString(R.string.ui_show))
            assertEquals("顯示", media.get().getString(R.string.ui_show))
            assertEquals("Show", application.getString(R.string.ui_show))
            assertEquals(originalConfiguration, application.resources.configuration)
            assertEquals(originalLocale, Locale.getDefault())
            assertEquals("已隱藏", notificationText())
        } finally {
            media.destroy()
            floating.destroy()
        }
    }

    @Test
    fun runningServices_followLanguageChanges_andResetToSystem() {
        val floating = Robolectric.buildService(FloatingLyricsService::class.java).create()
        val media = Robolectric.buildService(MediaNotificationListenerService::class.java).create()
        try {
            val feedback = ToastAirFeedback(floating.get()) { true }
            for ((mode, show, hidden) in listOf(
                Triple("zh-TW", "顯示", "已隱藏"),
                Triple("zh-CN", "显示", "已隐藏"),
                Triple("es", "Mostrar", "Oculto"),
                Triple("en", "Show", "Hidden"),
                Triple("zh-TW", "顯示", "已隱藏"),
                Triple("system", "Show", "Hidden")
            )) {
                LanguageSettingsStore.setMode(application, mode)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(show, floating.get().getString(R.string.ui_show))
                assertEquals(show, media.get().getString(R.string.ui_show))
                assertEquals(hidden, notificationText())
                feedback.showMessage(R.string.ui_show)
                assertEquals(show, ShadowToast.getTextOfLatestToast())
                assertEquals("Show", application.getString(R.string.ui_show))
            }
            feedback.dismiss()
        } finally {
            media.destroy()
            floating.destroy()
        }
    }

    @Test
    fun visibleOverlay_refreshesExistingStatusAndEmptyLyrics_afterLanguageSwitch() {
        ShadowSettings.setCanDrawOverlays(true)
        LanguageSettingsStore.setMode(application, "zh-TW")
        val controller = Robolectric.buildService(FloatingLyricsService::class.java).create()
        val service = controller.get()
        try {
            assertTrue(service.windowController.show())
            assertEquals(service.getString(R.string.ui_waiting_for_media_message), service.lyricsView?.text.toString())
            service.selectMediaSource(null)
            val traditionalStatus = service.lyricsView?.text.toString()
            LanguageSettingsStore.setMode(application, "en")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(service.getString(R.string.ui_no_media_source_status), service.lyricsView?.text.toString())
            LanguageSettingsStore.setMode(application, "zh-TW")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(traditionalStatus, service.lyricsView?.text.toString())

            service.renderer.parseAndShow(plainLrc = "", emptyText = {
                service.getString(R.string.ui_parsed_lyrics_are_empty)
            })
            LanguageSettingsStore.setMode(application, "en")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(service.getString(R.string.ui_parsed_lyrics_are_empty), service.lyricsView?.text.toString())
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun localizedResources_preserveConfigurationChanges_andSystemFallbacks() {
        LanguageSettingsStore.setMode(application, "zh-TW")
        val context = LocalizedServiceContext.wrap(application)
        assertEquals("顯示", context.getString(R.string.ui_show))
        assertSame(context.resources, context.resources)
        RuntimeEnvironment.setQualifiers("es-rES-land-night")
        assertEquals("顯示", context.getString(R.string.ui_show))
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, context.resources.configuration.orientation)
        assertEquals(Configuration.UI_MODE_NIGHT_YES,
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertEquals("es-ES", context.resources.configuration.locales[1].toLanguageTag())
        LanguageSettingsStore.setMode(application, "system")
        assertEquals("Mostrar", context.getString(R.string.ui_show))
    }

    @Test
    @Config(sdk = [33, 35])
    fun modernAndroid_keepsFrameworkContext() {
        assertSame(application, LocalizedServiceContext.wrap(application))
    }

    private fun notificationText(): String {
        val manager = application.getSystemService(NotificationManager::class.java)
        return shadowOf(manager).getNotification(FloatingServiceNotification.NOTIFICATION_ID)
            .extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    }
}
