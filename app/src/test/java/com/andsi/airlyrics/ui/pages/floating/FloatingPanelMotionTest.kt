package com.andsi.airlyrics.ui.pages.floating

import android.animation.ValueAnimator
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.SeekBar
import android.graphics.Color
import com.andsi.airlyrics.ui.components.SettingsPanelHeader
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode
import com.andsi.airlyrics.ui.components.AirTonalButtonTone
import com.andsi.airlyrics.ui.components.airTonalButtonStyle
import com.andsi.airlyrics.ui.state.FloatingPanelId
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloatingPanelMotionTest {
    private val controller = Robolectric.buildActivity(MainActivity::class.java)
    private val activity get() = controller.get()
    private val host get() = activity.graph.uiHost
    private lateinit var scope: FloatingPageScope
    private lateinit var page: View

    @Before fun setup() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        controller.setup().visible()
        host.setLyricsSwitchAnimationMode(LyricsSwitchAnimationMode.NONE)
        // These tests measure control motion, so no lyric playback loop should post frames.
        host.setWordByWordLyricsEnabled(false)
        scope = FloatingPageScope(host)
        page = scope.createView()
        activity.setContentView(page)
        settle()
        scope.restoringPanel = true
        scope.panelOpeners.getValue(FloatingPanelId.ANIMATION).invoke()
        settle()
    }

    @After fun cleanup() {
        controller.close()
        ShadowChoreographer.setPaused(false)
    }

    private fun all(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()

    private fun options() = all(requireNotNull(scope.activeBubble)).filterIsInstance<TextView>()
        .filter { it.isClickable && it.compoundDrawablesRelative[0] != null }

    private fun frame() {
        page.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        page.layout(0, 0, 360, 640)
        page.viewTreeObserver.dispatchOnPreDraw()
    }

    private fun advanceFrames(count: Int) {
        repeat(count) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
    }

    private fun settle() {
        frame()
        advanceFrames(20)
        frame()
    }

    private fun touch(view: View, action: Int) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, view.width / 2f, view.height / 2f, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun withoutAnimations(block: () -> Unit) {
        val scale = ReflectionHelpers.callStaticMethod<Float>(ValueAnimator::class.java, "getDurationScale")
        try {
            ReflectionHelpers.callStaticMethod<Any?>(ValueAnimator::class.java, "setDurationScale",
                ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType!!, 0f))
            block()
        } finally {
            ReflectionHelpers.callStaticMethod<Any?>(ValueAnimator::class.java, "setDurationScale",
                ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType!!, scale))
        }
    }

    @Test fun fastRepeatedSelectionKeepsFinalStateAndGeometry() {
        val options = options()
        val selected = options.single { it.isSelected }
        val next = options.first { !it.isSelected }
        val bounds = options.associateWith { Rect(it.left, it.top, it.right, it.bottom) }
        repeat(4) {
            next.performClick()
            advanceFrames(3)
            selected.performClick()
            advanceFrames(2)
        }
        next.performClick()
        settle()
        assertEquals(1, options.count { it.isSelected })
        assertTrue(next.isSelected)
        options.forEach { option ->
            assertEquals(bounds.getValue(option), Rect(option.left, option.top, option.right, option.bottom))
            assertEquals(1f, option.scaleX)
            assertEquals(1f, option.alpha)
            val tone = if (option.isSelected) AirTonalButtonTone.SELECTED else AirTonalButtonTone.DEFAULT
            assertEquals(host.airTonalButtonStyle(tone).fillColor,
                (option.background as GradientDrawable).color!!.defaultColor)
        }
        assertTrue(host.floatingPanelBackHandler!!.invoke())
        settle()
        assertNull(scope.activeBubble)
        assertTrue(page.isShown)
    }

    @Test fun colorSelectionAndSampleAnimateTogetherAndRetargetOnRapidTaps() {
        scope.panelOpeners.getValue(FloatingPanelId.TEXT_COLOR).invoke()
        settle()
        val bubble = requireNotNull(scope.activeBubble)
        val swatches = all(bubble).filterIsInstance<FloatingColorSwatch>()
        val blue = swatches.single { it.contentDescription == host.getString(R.string.ui_blue) }
        val red = swatches.single { it.contentDescription == host.getString(R.string.ui_red) }
        val sampleRow = all(bubble).single { it.getTag(R.id.interaction_anchor) == "floating.color.custom" }
        fun pixels(view: View): IntArray {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val pixels = IntArray(view.width * view.height)
            bitmap.getPixels(pixels, 0, view.width, 0, 0, view.width, view.height)
            bitmap.recycle()
            return pixels
        }
        blue.performClick()
        settle()
        val alpha = android.graphics.Color.alpha(host.floatingStyle().textColor)
        red.performClick()
        val firstMark = pixels(red)
        val firstSample = pixels(sampleRow)
        advanceFrames(3)
        val middleMark = pixels(red)
        val middleSample = pixels(sampleRow)
        settle()
        val finalMark = pixels(red)
        val finalSample = pixels(sampleRow)
        assertFalse("The selected mark must animate", firstMark.contentEquals(middleMark))
        assertFalse(middleMark.contentEquals(finalMark))
        assertFalse("The sample must blend between colors", firstSample.contentEquals(middleSample))
        assertFalse(middleSample.contentEquals(finalSample))
        repeat(3) {
            blue.performClick()
            advanceFrames(2)
            red.performClick()
            advanceFrames(2)
        }
        settle()
        assertTrue(red.isSelected)
        assertFalse(blue.isSelected)
        assertEquals(1, swatches.count { it.isSelected })
        assertTrue(finalMark.contentEquals(pixels(red)))
        assertTrue(finalSample.contentEquals(pixels(sampleRow)))
        assertEquals(alpha, android.graphics.Color.alpha(host.floatingStyle().textColor))
    }

    @Test fun rapidResetAndUndoNeverLiftOldFormsIntoAnOverlayOrFadeNewForms() {
        host.applyFloatingStyle(host.floatingStyle().copy(
            textColor = Color.argb(173, 12, 34, 56),
            wordByWordHighlightColor = Color.argb(91, 71, 83, 109),
            backgroundEnabled = true, backgroundAlpha = 77, backgroundColor = Color.rgb(19, 29, 39),
            shadowColor = Color.argb(113, 31, 41, 51), shadowRadius = 9f
        ))
        val initial = host.floatingStyle()
        for (id in listOf(FloatingPanelId.TEXT_COLOR, FloatingPanelId.HIGHLIGHT_COLOR,
            FloatingPanelId.BACKGROUND, FloatingPanelId.SHADOW)) {
            scope.panelOpeners.getValue(id).invoke()
            settle()
            val bubble = requireNotNull(scope.activeBubble)
            val header = all(bubble).filterIsInstance<SettingsPanelHeader>().single()
            fun action(label: Int) = all(bubble).single {
                it.isShown && it.isClickable && it.contentDescription == host.getString(label)
            }
            fun form() = all(bubble).filterIsInstance<FloatingColorPanelContent>().single()
            fun replace(label: Int) {
                val outgoing = form()
                action(label).performClick()
                frame()
                val incoming = form()
                assertNotSame(outgoing, incoming)
                assertNull("Removed form must not escape its clipped viewport through an overlay", outgoing.parent)
                assertSame(header, all(bubble).filterIsInstance<SettingsPanelHeader>().single())
                // Check the first frames too: the flash is transient and is gone once settled.
                repeat(2) {
                    assertEquals(1f, ReflectionHelpers.callInstanceMethod<Float>(incoming, "getTransitionAlpha"))
                    assertEquals(1f, bubble.alpha)
                    assertNull(outgoing.parent)
                    advanceFrames(1)
                }
            }
            repeat(2) { pageIndex ->
                if (pageIndex == 1) {
                    all(bubble).single {
                        it.getTag(R.id.interaction_anchor) == "floating.color.custom"
                    }.performClick()
                    frame()
                    // Reset while the overview -> RGB page transition is still running.
                    advanceFrames(2)
                }
                repeat(3) {
                    replace(R.string.ui_reset)
                    // Undo before the container resize from reset has finished.
                    replace(R.string.ui_undo)
                    assertEquals(initial, host.floatingStyle())
                }
                settle()
                if (pageIndex == 1) {
                    assertEquals(3, all(bubble).filterIsInstance<SeekBar>().count { it.isShown })
                }
            }
        }
    }

    @Test fun resetBeforeThePageTransitionStartsKeepsTheRgbPageActive() {
        host.applyFloatingStyle(host.floatingStyle().copy(textColor = Color.argb(173, 12, 34, 56)))
        val initial = host.floatingStyle()
        scope.panelOpeners.getValue(FloatingPanelId.TEXT_COLOR).invoke()
        settle()
        val bubble = requireNotNull(scope.activeBubble)
        val outgoing = all(bubble).filterIsInstance<FloatingColorPanelContent>().single()
        all(bubble).single { it.getTag(R.id.interaction_anchor) == "floating.color.custom" }.performClick()
        // No pre-draw between navigation and reset: cancel the pending transition as well.
        all(bubble).single { it.contentDescription == host.getString(R.string.ui_reset) }.performClick()
        val incoming = all(bubble).filterIsInstance<FloatingColorPanelContent>().single()
        frame()
        assertNull("A canceled pre-draw listener must not restart the old form's Fade", outgoing.parent)
        assertEquals(1f, ReflectionHelpers.callInstanceMethod<Float>(incoming, "getTransitionAlpha"))
        all(bubble).single { it.contentDescription == host.getString(R.string.ui_undo) }.performClick()
        settle()
        assertEquals(initial, host.floatingStyle())
        assertEquals(3, all(bubble).filterIsInstance<SeekBar>().count { it.isShown })
        assertTrue(all(bubble).single { it.contentDescription == host.getString(R.string.ui_back) }.isShown)
    }

    @Test fun disabledAnimationsKeepPressAndSelectionImmediate() = withoutAnimations {
        val next = options().first { !it.isSelected }
        touch(next, MotionEvent.ACTION_DOWN)
        assertEquals(1f, next.scaleX)
        touch(next, MotionEvent.ACTION_UP)
        settle()
        assertTrue(next.isSelected)
        assertEquals(1f, next.scaleX)
        assertEquals(1f, next.alpha)
        assertEquals(host.airTonalButtonStyle(AirTonalButtonTone.SELECTED).fillColor,
            (next.background as GradientDrawable).color!!.defaultColor)
        assertTrue(host.floatingPanelBackHandler!!.invoke())
        assertNull(scope.activeBubble)
    }

    @Test fun cancelledPressReturnsToRestWithoutChangingSelection() {
        val next = options().first { !it.isSelected }
        touch(next, MotionEvent.ACTION_DOWN)
        advanceFrames(5)
        touch(next, MotionEvent.ACTION_CANCEL)
        settle()
        assertFalse(next.isSelected)
        assertEquals(1f, next.scaleX)
        assertEquals(1f, next.alpha)
        assertEquals(1, options().count { it.isSelected })
    }
}
