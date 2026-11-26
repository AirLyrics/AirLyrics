package com.andsi.airlyrics.floating

import android.content.Context
import android.graphics.Insets
import android.graphics.Rect
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.core.model.FloatingPosition
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSettings
import java.time.Duration
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class FloatingWindowAreaTest {
    private val statusBars = WindowInsets.Type.statusBars()
    private val fullFit = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var window: FloatingLyricsWindow? = null

    @After fun closeWindow() {
        window?.hide()
    }

    @Test fun fullStatusBarFrameKeepsPlatformFitting() {
        val area = windowArea(statusBarBottom = 32, gestureTop = 32)
        assertEquals(FloatingWindowArea(400, 744, fitInsetsTypes = fullFit), area)
    }

    @Test fun aShortFrameExposesTheSpaceBelowTheBarAndCutout() {
        val area = windowArea(statusBarBottom = 21, cutoutTop = 20)
        assertEquals(FloatingWindowArea(400, 755, 1, 12, fullFit and statusBars.inv()), area)
    }

    @Test fun keepsTheFittedBoundaryWithoutTrustworthyClearance() {
        val fitted = FloatingWindowArea(400, 744, fitInsetsTypes = fullFit)

        assertEquals(fitted, windowArea())
        assertEquals(fitted, windowArea(statusVisible = false))
        assertEquals(fitted, windowArea(statusBarBottom = 21, reservedBarBottom = null))
        assertEquals(fitted, windowArea(statusBarBottom = 21, insetFrameHeight = 700))
        assertEquals(fitted, windowArea(statusBarBottom = 21, cutoutTop = 32))
        assertEquals(fitted, windowArea(statusBarBottom = 21, gestureTop = 32))
    }

    @Test fun hiddenStatusBarStillReservesItsReportedArea() {
        val visible = windowArea(statusBarBottom = 21)
        val hidden = windowArea(statusVisible = false, reservedBarBottom = 21)

        assertEquals(visible, hidden)
    }

    @Test fun respectsBothVisibleAndReservedStatusBarBounds() {
        assertEquals(748, windowArea(statusBarBottom = 28, reservedBarBottom = 21).height)
        assertEquals(748, windowArea(statusBarBottom = 21, reservedBarBottom = 28).height)
    }

    @Test fun shortFrameSetsTheFitMaskAndVerticalDragEndpoints() {
        FloatingLyricsStyleStore.saveRelativePosition(context, FloatingPosition(0.5f, 0f))
        val controller = createWindow { windowArea(statusBarBottom = 21, cutoutTop = 20) }
        val view = show(controller)
        val params = view.overlayParams
        assertEquals(fullFit and statusBars.inv(), params.fitInsetsTypes)
        assertEquals(21, params.y + 20)

        view.touch(MotionEvent.ACTION_DOWN, y = 15f)
        view.touch(MotionEvent.ACTION_MOVE, y = 2000f)
        assertEquals(800 - 24, params.y + 20 + view.height)
        view.touch(MotionEvent.ACTION_MOVE, y = -2000f)
        view.touch(MotionEvent.ACTION_UP, y = -2000f)
        assertEquals(21, params.y + 20)
        assertEquals(0f, requireNotNull(FloatingLyricsStyleStore.getRelativePosition(context)).verticalTravelFraction)
    }

    @Test fun upgradePreservesScreenYAcrossASecondShow() {
        val controller = createWindow { windowArea(statusBarBottom = 21, cutoutTop = 20) }
        for (fraction in listOf(0f, 0.42f, 1f)) {
            saveOldFractions(vertical = fraction)

            val view = show(controller)
            val expectedScreenY = oldScreenY(fraction, view)
            assertEquals(expectedScreenY, view.overlayParams.y + 20)
            assertFalse(FloatingLyricsStyleStore.usesLegacyVerticalPosition(context))
            val migrated = requireNotNull(FloatingLyricsStyleStore.getRelativePosition(context))
            assertEquals(0.25f, migrated.horizontalTravelFraction)

            controller.hide()
            val reopened = show(controller)
            assertEquals(expectedScreenY, reopened.overlayParams.y + 20)
            assertEquals(migrated, FloatingLyricsStyleStore.getRelativePosition(context))
            controller.hide()
        }
    }

    @Test fun migrationWaitsWhenTheOverlayHasNoVerticalTravel() {
        val expanded = windowArea(statusBarBottom = 21, cutoutTop = 20)
        var area = expanded.copy(height = 20)
        val fraction = 0.8f
        saveOldFractions(vertical = fraction)
        val view = show(createWindow { area })

        view.touch(MotionEvent.ACTION_DOWN, y = 10f)
        view.touch(MotionEvent.ACTION_MOVE, x = 1000f, y = 10f)
        view.touch(MotionEvent.ACTION_UP, x = 1000f, y = 10f)
        assertTrue(FloatingLyricsStyleStore.usesLegacyVerticalPosition(context))
        assertEquals(FloatingPosition(1f, fraction), FloatingLyricsStyleStore.getRelativePosition(context))

        area = expanded
        view.rootView.dispatchApplyWindowInsets(WindowInsets.CONSUMED)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(oldScreenY(fraction, view), view.overlayParams.y + 20)
        assertFalse(FloatingLyricsStyleStore.usesLegacyVerticalPosition(context))
    }

    @Test fun dragMovesReuseTheAreaUntilRelease() {
        val area = windowArea(statusBarBottom = 21, cutoutTop = 20)
        var areaReads = 0
        val controller = createWindow {
            areaReads++
            area
        }
        val view = show(controller)
        val initialReads = areaReads

        view.touch(MotionEvent.ACTION_DOWN, y = 20f)
        for (y in listOf(60f, 100f, 140f)) {
            view.touch(MotionEvent.ACTION_MOVE, y = y)
            view.rootView.dispatchApplyWindowInsets(WindowInsets.CONSUMED)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(initialReads, areaReads)
        view.touch(MotionEvent.ACTION_UP, y = 140f)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(initialReads + 1, areaReads)
    }

    @Test fun aClippedInsetChangeStillRefreshesTheAreaDuringDrag() {
        var area = windowArea(statusBarBottom = 21, cutoutTop = 20)
        FloatingLyricsStyleStore.saveRelativePosition(context, FloatingPosition(0.5f, 0f))
        val view = show(createWindow { area })
        assertEquals(fullFit and statusBars.inv(), view.overlayParams.fitInsetsTypes)

        view.touch(MotionEvent.ACTION_DOWN, y = 20f)
        view.touch(MotionEvent.ACTION_MOVE, y = 80f)
        area = windowArea(statusBarBottom = 32)
        view.rootView.dispatchApplyWindowInsets(WindowInsets.CONSUMED)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(160))
        assertEquals(fullFit, view.overlayParams.fitInsetsTypes)
        assertEquals(0, view.overlayParams.y)
    }

    private fun createWindow(readArea: () -> FloatingWindowArea): FloatingLyricsWindow {
        ShadowSettings.setCanDrawOverlays(true)
        return FloatingLyricsWindow(context, readArea) {}.also { window = it }
    }

    private fun show(controller: FloatingLyricsWindow): View {
        assertTrue(controller.show())
        shadowOf(Looper.getMainLooper()).idle()
        return requireNotNull(controller.textView)
    }

    private val View.overlayParams get() = rootView.layoutParams as WindowManager.LayoutParams

    private fun View.touch(action: Int, x: Float = 20f, y: Float) {
        val event = MotionEvent.obtain(0, 0, action, x, y, 0)
        try {
            assertTrue(dispatchTouchEvent(event))
        } finally {
            event.recycle()
        }
    }

    private fun saveOldFractions(vertical: Float) {
        val saved = context.getSharedPreferences("floating_lyrics_style", Context.MODE_PRIVATE).edit()
            .clear()
            .putFloat("position_horizontal_fraction", 0.25f)
            .putFloat("position_vertical_fraction", vertical)
            .commit()
        assertTrue(saved)
    }

    private fun oldScreenY(fraction: Float, view: View): Int =
        32 + (fraction * (800 - 32 - 24 - view.height)).roundToInt()

    private fun windowArea(
        statusVisible: Boolean = true,
        statusBarBottom: Int? = null,
        reservedBarBottom: Int? = statusBarBottom,
        cutoutTop: Int = 0,
        gestureTop: Int = 0,
        insetFrameHeight: Int = 800,
    ): FloatingWindowArea {
        val statusInsets = Insets.of(0, 32, 0, 0)
        val builder = WindowInsets.Builder()
            .setFrame(400, insetFrameHeight)
            .setInsetsIgnoringVisibility(statusBars, statusInsets)
            .setInsetsIgnoringVisibility(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 24))
            .setInsetsIgnoringVisibility(WindowInsets.Type.displayCutout(), Insets.of(0, cutoutTop, 0, 0))
            .setInsets(statusBars, if (statusVisible) statusInsets else Insets.NONE)
            .setInsetsIgnoringVisibility(WindowInsets.Type.mandatorySystemGestures(), Insets.of(0, gestureTop, 0, 0))
            .setVisible(statusBars, statusVisible)
        statusBarBottom?.let { bottom ->
            builder.setBoundingRects(statusBars, listOf(Rect(0, 0, 400, bottom)))
        }
        reservedBarBottom?.let { bottom ->
            builder.setBoundingRectsIgnoringVisibility(statusBars, listOf(Rect(0, 0, 400, bottom)))
        }
        return FloatingWindowArea.fromInsets(400, 800, builder.build())
    }
}
