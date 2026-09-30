package com.andsi.airlyrics.floating

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.view.MotionEvent
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.FloatingPosition
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 30, 35])
@LooperMode(LooperMode.Mode.PAUSED)
class FloatingLyricsRotationTest {
    private lateinit var application: Application
    private lateinit var controller: ServiceController<FloatingLyricsService>
    private val service get() = controller.get()

    @Before fun setup() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("floating_lyrics_style", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(application).grantPermissions("com.andsi.airlyrics.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        ShadowSettings.setCanDrawOverlays(true)
        RuntimeEnvironment.setQualifiers("w400dp-h800dp-port")
        ShadowDisplayManager.changeDisplay(0, "w400dp-h800dp-port")
        controller = Robolectric.buildService(FloatingLyricsService::class.java).create()
    }

    @After fun cleanup() { if (::controller.isInitialized) controller.destroy() }

    private fun show() {
        assertTrue(service.windowController.show())
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun rotate(landscape: Boolean) {
        val qualifiers = if (landscape) "w800dp-h400dp-land" else "w400dp-h800dp-port"
        RuntimeEnvironment.setQualifiers(qualifiers)
        ShadowDisplayManager.changeDisplay(0, qualifiers)
        // Robolectric does not deliver the window server's configuration to WindowContext.
        @Suppress("DEPRECATION")
        service.lyricsView?.context?.resources?.updateConfiguration(
            application.resources.configuration, application.resources.displayMetrics
        )
        service.onConfigurationChanged(Configuration(service.resources.configuration).apply {
            orientation = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        })
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun rotationResizesWithoutReplacingViewOrChangingSavedPosition() {
        FloatingLyricsStyleStore.saveRelativePosition(application, FloatingPosition(0.5f, 0.75f))
        show()
        val view = requireNotNull(service.lyricsView)
        view.text = "A lyric that survives rotation"
        val params = view.layoutParams as WindowManager.LayoutParams
        val width = params.width
        val x = params.x
        val saved = FloatingLyricsStyleStore.getRelativePosition(application)
        repeat(3) {
            rotate(true)
            assertSame(view, service.lyricsView)
            assertEquals("A lyric that survives rotation", view.text.toString())
            assertTrue("Landscape width ${params.width} must exceed portrait $width", params.width > width)
            assertTrue(params.x > x)
            assertEquals(saved, FloatingLyricsStyleStore.getRelativePosition(application))
            rotate(false)
            assertEquals(width, params.width)
            assertEquals(x, params.x)
        }
    }

    @Test fun hiddenWindowStaysHiddenAndQueuedRefreshIsCancelledOnHide() {
        rotate(true)
        assertNull(service.lyricsView)
        show()
        service.onConfigurationChanged(Configuration())
        service.windowController.hide()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(service.lyricsView)
    }

    @Test fun configurationChangeCancelsDragWithoutSavingTransientCoordinates() {
        val saved = FloatingPosition(0.5f, 0.5f)
        FloatingLyricsStyleStore.saveRelativePosition(application, saved)
        show()
        val view = requireNotNull(service.lyricsView)
        fun touch(action: Int, x: Float) {
            val event = MotionEvent.obtain(0, 0, action, x, 20f, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
        touch(MotionEvent.ACTION_DOWN, 10f)
        touch(MotionEvent.ACTION_MOVE, 40f)
        rotate(true)
        touch(MotionEvent.ACTION_UP, 40f)
        assertEquals(saved, FloatingLyricsStyleStore.getRelativePosition(application))
    }

    @Test fun legacyPixelsMigrateOnceAndSurviveRotation() {
        FloatingLyricsStyleStore.savePosition(application, 0, 200)
        show()
        val params = requireNotNull(service.lyricsView).layoutParams as WindowManager.LayoutParams
        assertEquals(0, params.x)
        val migrated = requireNotNull(FloatingLyricsStyleStore.getRelativePosition(application))
        rotate(true)
        assertEquals(migrated, FloatingLyricsStyleStore.getRelativePosition(application))
        service.windowController.hide()
        show()
        assertEquals(migrated, FloatingLyricsStyleStore.getRelativePosition(application))
        assertEquals(0 to 200, FloatingLyricsStyleStore.getPosition(application))
    }

    @Test fun landscapeStartupAndStyleChangesPreserveCenter() {
        rotate(true)
        show()
        assertEquals(0.5f, requireNotNull(FloatingLyricsStyleStore.getRelativePosition(application)).horizontal)
        val view = requireNotNull(service.lyricsView)
        val params = view.layoutParams as WindowManager.LayoutParams
        val originalWidth = params.width
        val originalX = params.x
        FloatingLyricsStyleStore.setMaxWidthPercent(application, 45)
        assertTrue(service.windowController.applyStyle())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(params.width < originalWidth)
        assertTrue(kotlin.math.abs((originalX + originalWidth / 2) - (params.x + params.width / 2)) <= 1)
    }

    @Test fun contentHeightChangesOnlyClampAndNeverOverwritePreferredPosition() {
        val saved = FloatingPosition(0.5f, 0.1f)
        FloatingLyricsStyleStore.saveRelativePosition(application, saved)
        show()
        val view = requireNotNull(service.lyricsView)
        val params = view.layoutParams as WindowManager.LayoutParams
        val initialY = params.y
        view.text = "First line\nSecond line"
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(initialY, params.y)
        view.text = "Long lyric\n".repeat(100)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, params.y)
        assertTrue(view.height <= view.maxHeight)
        assertEquals(saved, FloatingLyricsStyleStore.getRelativePosition(application))
    }

    @Test fun dragClampsAtEdgeAndRestoresAfterHide() {
        show()
        val view = requireNotNull(service.lyricsView)
        listOf(MotionEvent.ACTION_DOWN to 0f, MotionEvent.ACTION_MOVE to 10000f, MotionEvent.ACTION_UP to 10000f)
            .forEach { (action, x) ->
                val event = MotionEvent.obtain(0, 0, action, x, 20f, 0)
                view.dispatchTouchEvent(event)
                event.recycle()
            }
        val saved = requireNotNull(FloatingLyricsStyleStore.getRelativePosition(application))
        assertEquals(1f, saved.horizontal)
        val x = (view.layoutParams as WindowManager.LayoutParams).x
        service.windowController.hide()
        show()
        assertEquals(x, (requireNotNull(service.lyricsView).layoutParams as WindowManager.LayoutParams).x)
        assertEquals(saved, FloatingLyricsStyleStore.getRelativePosition(application))
    }

    @Test fun lockAndClickThroughSurviveRotation() {
        show()
        service.windowController.setLocked(true)
        service.windowController.setClickThrough(true)
        val view = requireNotNull(service.lyricsView)
        val flags = (view.layoutParams as WindowManager.LayoutParams).flags
        rotate(true)
        assertSame(view, service.lyricsView)
        assertEquals(flags, (view.layoutParams as WindowManager.LayoutParams).flags)
        assertTrue(FloatingLyricsStyleStore.isLocked(application))
    }
}
