package com.andsi.airlyrics.ui.pages.floating

import android.animation.ValueAnimator
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.SeekBar
import android.graphics.Color
import com.andsi.airlyrics.ui.components.SettingsPanelHeader
import com.andsi.airlyrics.ui.components.SettingsSliderRow
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

    private fun touch(view: View, action: Int, fraction: Float = .5f) {
        val now = SystemClock.uptimeMillis()
        val x = view.paddingLeft + (view.width - view.paddingLeft - view.paddingRight) * fraction
        val event = MotionEvent.obtain(now, now, action, x, view.height / 2f, 0)
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

    private fun pixels(view: View): IntArray {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val pixels = IntArray(view.width * view.height)
        bitmap.getPixels(pixels, 0, view.width, 0, 0, view.width, view.height)
        bitmap.recycle()
        return pixels
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

    @Test fun colorSelectionWipesRightAndRetargetsOnRapidTaps() {
        scope.panelOpeners.getValue(FloatingPanelId.TEXT_COLOR).invoke()
        settle()
        val bubble = requireNotNull(scope.activeBubble)
        val swatches = all(bubble).filterIsInstance<FloatingColorSwatch>()
        val blue = swatches.single { it.contentDescription == host.getString(R.string.ui_blue) }
        val red = swatches.single { it.contentDescription == host.getString(R.string.ui_red) }
        val sample = control("floating.color.custom.sample")
        val sampleRow = control("floating.color.custom")
        val blueColor = Color.rgb(66, 165, 245)
        val redColor = Color.rgb(239, 83, 80)
        val middle = sample.height / 2 * sample.width
        val left = middle + sample.width / 4
        val right = middle + sample.width * 3 / 4
        blue.performClick()
        settle()
        val alpha = Color.alpha(host.floatingStyle().textColor)
        red.performClick()
        val firstMark = pixels(red)
        assertEquals(blueColor, pixels(sample)[middle + sample.width / 2])
        assertTrue(sampleRow.contentDescription.toString().endsWith("#EF5350"))
        advanceFrames(3)
        val middleMark = pixels(red)
        var sawSplit = false
        repeat(10) {
            val current = pixels(sample)
            sawSplit = sawSplit || (current[left] == redColor && current[right] == blueColor)
            advanceFrames(1)
        }
        assertTrue("The left side reveals the new color before the right", sawSplit)
        settle()
        val finalMark = pixels(red)
        val finalSample = pixels(sample)
        assertFalse("The selected mark must animate", firstMark.contentEquals(middleMark))
        assertFalse(middleMark.contentEquals(finalMark))
        assertEquals(redColor, finalSample[left])
        assertEquals(redColor, finalSample[right])
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
        assertTrue(finalSample.contentEquals(pixels(sample)))
        assertEquals(alpha, Color.alpha(host.floatingStyle().textColor))
    }

    @Test fun pinkAndRedWipeRightOnTouchFromEitherInitialColor() {
        val pink = Color.rgb(236, 64, 122)
        val red = Color.rgb(239, 83, 80)
        for ((from, to, label) in listOf(Triple(pink, red, R.string.ui_red), Triple(red, pink, R.string.ui_pink))) {
            host.applyFloatingTextColor(from, refreshPage = false)
            scope.panelOpeners.getValue(FloatingPanelId.TEXT_COLOR).invoke()
            settle()
            val sample = control("floating.color.custom.sample")
            val swatch = all(requireNotNull(scope.activeBubble)).filterIsInstance<FloatingColorSwatch>().single {
                it.contentDescription == host.getString(label)
            }
            val bounds = Rect(0, 0, swatch.width, swatch.height)
            (page as ViewGroup).offsetDescendantRectToMyCoords(swatch, bounds)
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, now, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                try { assertTrue(page.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(to and 0xFFFFFF, host.floatingStyle().textColor and 0xFFFFFF)
            val middle = sample.height / 2 * sample.width
            val left = middle + sample.width / 4
            val right = middle + sample.width * 3 / 4
            assertEquals(from, pixels(sample)[middle + sample.width / 2])
            val edgeThreshold = minOf(ColorUtils.calculateLuminance(from), ColorUtils.calculateLuminance(to)) * .8
            var sawVisibleEdge = false
            repeat(12) {
                advanceFrames(1)
                val frame = pixels(sample)
                if (frame[left] == to && frame[right] == from) {
                    sawVisibleEdge = sawVisibleEdge || (left + 1 until right).any {
                        ColorUtils.calculateLuminance(frame[it]) < edgeThreshold
                    }
                }
            }
            assertTrue("Expected a visible rightward wipe from ${compactColorSummary(from)} to ${compactColorSummary(to)}", sawVisibleEdge)
            settle()
            assertEquals(to, pixels(sample)[middle + sample.width / 2])
        }
    }

    @Test fun paletteAndCustomColorsWipeLeftOnUndoAndReset() {
        val pink = Color.rgb(236, 64, 122)
        val red = Color.rgb(239, 83, 80)
        val custom = Color.rgb(18, 52, 86)
        val alpha = 173
        val initial = host.floatingStyle()
        val defaults = host.floatingStyleDefaults(initial.presetName)
        val defaultRgb = defaults.textColor or Color.BLACK
        for (previous in listOf(pink, custom)) {
            host.applyFloatingStyle(initial.copy(textColor = if (previous == pink) {
                Color.argb(alpha, 255, 255, 255)
            } else Color.argb(alpha, 18, 52, 86)))
            scope.panelOpeners.getValue(FloatingPanelId.TEXT_COLOR).invoke()
            settle()
            val form = requireNotNull(scope.activeBubble)
            val sample = control("floating.color.custom.sample")
            val customRow = control("floating.color.custom")
            val reset = control("floating.panel.reset") as TextView
            val undo = control("floating.panel.undo")
            fun swatch(label: Int) = all(form).filterIsInstance<FloatingColorSwatch>().single {
                it.contentDescription == host.getString(label)
            }
            if (previous == pink) {
                swatch(R.string.ui_pink).performClick()
                settle()
            }
            swatch(R.string.ui_red).performClick()
            settle()
            val enabledInk = reset.currentTextColor

            fun assertStoredColor(rgb: Int) {
                assertEquals(rgb and 0xFFFFFF, host.floatingStyle().textColor and 0xFFFFFF)
                assertEquals(alpha, Color.alpha(host.floatingStyle().textColor))
                assertTrue(customRow.contentDescription.toString().endsWith(compactColorSummary(rgb)))
                assertSame(sample, control("floating.color.custom.sample"))
            }

            fun assertWipe(from: Int, to: Int) {
                assertStoredColor(to)
                val middle = sample.height / 2 * sample.width
                val left = middle + sample.width / 4
                val right = middle + sample.width * 3 / 4
                val start = pixels(sample)
                assertEquals("The first frame keeps the previous color", from, start[middle + sample.width / 2])
                var sawSplit = false
                repeat(10) {
                    advanceFrames(1)
                    val current = pixels(sample)
                    assertEquals("The moving color stays in the circle", Color.TRANSPARENT, current[0])
                    sawSplit = sawSplit || (current[left] == from && current[right] == to)
                }
                assertTrue("The right side must reveal the new color before the left", sawSplit)
                settle()
                assertEquals(to, pixels(sample)[left])
                assertEquals(to, pixels(sample)[right])
            }

            undo.performClick()
            assertTrue(reset.isEnabled)
            assertWipe(red, previous)
            if (previous == pink) assertTrue(swatch(R.string.ui_pink).isSelected)
            assertFalse(swatch(R.string.ui_red).isSelected)

            touch(reset, MotionEvent.ACTION_DOWN)
            advanceFrames(3)
            touch(reset, MotionEvent.ACTION_UP)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(reset.isEnabled)
            assertTrue(undo.isEnabled)
            val disabledInk = reset.currentTextColor
            assertEquals((Color.alpha(enabledInk) + 1) / 2, Color.alpha(disabledInk))
            val tint = requireNotNull(reset.compoundDrawableTintList)
            assertEquals(disabledInk, tint.getColorForState(reset.drawableState, Color.TRANSPARENT))
            assertWipe(previous, defaultRgb)
            assertEquals(1f, reset.alpha)
            assertEquals(disabledInk, reset.currentTextColor)
            reset.performClick()
            assertTrue(undo.isEnabled)
            assertStoredColor(defaultRgb)

            undo.performClick()
            assertTrue(reset.isEnabled)
            assertEquals(enabledInk, reset.currentTextColor)
            assertWipe(defaultRgb, previous)
            withoutAnimations {
                reset.performClick()
                assertStoredColor(defaultRgb)
                assertEquals(defaultRgb, pixels(sample)[sample.height / 2 * sample.width + sample.width / 2])
            }
        }
    }

    @Test fun rapidResetAndUndoPreserveTheOpenFormAndNestedNavigation() {
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
            val form = all(bubble).filterIsInstance<FloatingColorPanelContent>().single()
            val header = all(bubble).filterIsInstance<SettingsPanelHeader>().single()
            val reset = control("floating.panel.reset")
            val undo = control("floating.panel.undo")
            repeat(2) { pageIndex ->
                if (pageIndex == 1) {
                    control("floating.color.custom").performClick()
                }
                repeat(2) {
                    reset.performClick()
                    assertTrue(undo.isEnabled)
                    undo.performClick()
                    undo.performClick()
                    assertFalse(undo.isEnabled)
                    assertEquals(initial, host.floatingStyle())
                    frame()
                    assertSame(form, all(bubble).filterIsInstance<FloatingColorPanelContent>().single())
                    assertSame(header, all(bubble).filterIsInstance<SettingsPanelHeader>().single())
                    assertEquals(1f, ReflectionHelpers.callInstanceMethod<Float>(form, "getTransitionAlpha"))
                    assertEquals(1f, bubble.alpha)
                    advanceFrames(1)
                }
                settle()
                if (pageIndex == 1) {
                    assertEquals(3, all(bubble).filterIsInstance<SeekBar>().count { it.isShown })
                    assertTrue(all(bubble).single { it.contentDescription == host.getString(R.string.ui_back) }.isShown)
                }
            }
        }
    }

    @Test fun opacityDragCommitsOnceAndUndoCoordinatesTheSliderAndValueDuringInterruption() {
        val initial = host.floatingStyle().copy(textColor = Color.argb(opacityPercentToAlpha(85), 30, 40, 50))
        host.applyFloatingStyle(initial)
        scope.panelOpeners.getValue(FloatingPanelId.TEXT_OPACITY).invoke()
        settle()
        val row = all(requireNotNull(scope.activeBubble)).filterIsInstance<SettingsSliderRow>().single()
        val slider = row.seekBar
        val undo = control("floating.panel.undo")
        touch(slider, MotionEvent.ACTION_DOWN, .3f)
        touch(slider, MotionEvent.ACTION_MOVE, .7f)
        touch(slider, MotionEvent.ACTION_MOVE, .2f)
        assertEquals(initial, host.floatingStyle())
        assertEquals("${slider.progress}%", row.valueView.text.toString())
        assertFalse(undo.isEnabled)
        touch(slider, MotionEvent.ACTION_UP, .2f)

        val edited = slider.progress
        assertTrue(edited < 40)
        assertEquals(opacityPercentToAlpha(edited), Color.alpha(host.floatingStyle().textColor))
        assertTrue(undo.contentDescription.toString().contains("85%"))
        frame()
        val valueHint = all(undo).filterIsInstance<TextView>().single { it.text.toString() == "85%" }
        assertTrue(valueHint.isShown)
        assertTrue(valueHint.width > 0 && valueHint.height > 0)
        undo.performClick()
        undo.performClick()
        assertFalse(undo.isEnabled)
        assertEquals(initial, host.floatingStyle())
        advanceFrames(3)
        assertTrue(slider.progress in (edited + 1) until 85)
        assertEquals("${slider.progress}%", row.valueView.text.toString())

        touch(slider, MotionEvent.ACTION_DOWN, .5f)
        touch(slider, MotionEvent.ACTION_MOVE, .6f)
        touch(slider, MotionEvent.ACTION_CANCEL, .6f)
        assertEquals(initial, host.floatingStyle())
        assertEquals(85, slider.progress)
        assertFalse(undo.isEnabled)

        touch(slider, MotionEvent.ACTION_DOWN, .4f)
        touch(slider, MotionEvent.ACTION_UP, .4f)
        assertTrue(undo.isEnabled)
        withoutAnimations {
            undo.performClick()
            assertEquals(initial, host.floatingStyle())
            assertEquals(85, slider.progress)
            assertEquals("85%", row.valueView.text.toString())
        }
        touch(slider, MotionEvent.ACTION_DOWN, .3f)
        touch(slider, MotionEvent.ACTION_UP, .3f)
        val beforeReset = host.floatingStyle()
        val beforeResetProgress = slider.progress
        val reset = control("floating.panel.reset")
        val defaults = host.floatingStyleDefaults(initial.presetName)
        val defaultProgress = alphaToOpacityPercent(Color.alpha(defaults.textColor))
        reset.performClick()
        assertFalse(reset.isEnabled)
        assertTrue(undo.isEnabled)
        assertEquals(Color.alpha(defaults.textColor), Color.alpha(host.floatingStyle().textColor))
        assertEquals(beforeResetProgress, slider.progress)
        advanceFrames(3)
        assertTrue(slider.progress in (beforeResetProgress + 1) until defaultProgress)
        assertEquals("${slider.progress}%", row.valueView.text.toString())
        undo.performClick()
        scope.panelOpeners.getValue(FloatingPanelId.WINDOW_LAYOUT).invoke()
        settle()
        assertFalse(control("floating.panel.undo").isEnabled)
        assertEquals(beforeResetProgress, slider.progress)
        assertEquals(beforeReset, host.floatingStyle())
    }

    private fun control(tag: String): View = all(requireNotNull(scope.activeBubble)).single {
        it.getTag(R.id.interaction_anchor) == tag
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
