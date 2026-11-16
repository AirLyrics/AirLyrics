package com.andsi.airlyrics.ui.pages.floating

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.MainActivity
import java.time.Duration
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowChoreographer

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloatingColorSaveButtonTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var frame: FrameLayout
    private lateinit var button: FloatingColorSaveButton
    private lateinit var sibling: View
    private var clicks = 0
    private var originalScale = 1f

    @Before fun setUp() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        originalScale = ReflectionHelpers.callStaticMethod(ValueAnimator::class.java, "getDurationScale")
        setAnimationScale(1f)
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val host = activity.graph.uiHost
        button = FloatingColorSaveButton(host) { clicks++ }
        sibling = View(activity).apply { minimumHeight = 45 }
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(sibling, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 45))
            addView(button)
        }
        frame = FrameLayout(activity).apply {
            isFocusableInTouchMode = true
            addView(column, FrameLayout.LayoutParams(320, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        activity.setContentView(frame)
        controller.visible()
        frame.requestFocus()
        frame.measure(exact(360), exact(640))
        frame.layout(0, 0, 360, 640)
        advance(32)
        button.render(ColorSaveStatus.UNSAVED, canSave = true)
    }

    @After fun tearDown() {
        controller.close()
        setAnimationScale(originalScale)
        ShadowChoreographer.setPaused(false)
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun advance(ms: Long) {
        layoutFrame()
        var remaining = ms
        while (remaining > 0) {
            val step = minOf(remaining, 16L)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            remaining -= step
        }
        layoutFrame()
    }
    private fun layoutFrame() {
        frame.measure(exact(360), exact(640))
        frame.layout(0, 0, 360, 640)
        frame.viewTreeObserver.dispatchOnPreDraw()
        check(button.width > 0 && button.height > 0)
    }
    private fun bounds(view: View) = Rect(view.left, view.top, view.right, view.bottom)
    private fun pixel(x: Int, y: Int): Int {
        val bitmap = Bitmap.createBitmap(button.width, button.height, Bitmap.Config.ARGB_8888)
        button.draw(Canvas(bitmap))
        return bitmap.getPixel(x, y).also { bitmap.recycle() }
    }
    private fun capture(name: String) {
        val bitmap = Bitmap.createBitmap(button.width, button.height, Bitmap.Config.ARGB_8888)
        button.draw(Canvas(bitmap))
        File("/tmp/airlyrics-save-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    private fun setAnimationScale(scale: Float) {
        ReflectionHelpers.callStaticMethod<Any?>(ValueAnimator::class.java, "setDurationScale",
            ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, scale))
    }

    @Test fun fastResultKeepsTheShortWaitThenRevealsACircleWithoutMovingLayout() {
        val buttonBounds = bounds(button)
        val siblingBounds = bounds(sibling)
        val height = frame.getChildAt(0).height
        capture("idle")
        button.performClick()
        button.render(ColorSaveStatus.SAVING, canSave = false)
        repeat(10) { button.performClick() }
        assertEquals(1, clicks)
        advance(64)
        assertTrue(button.scaleX < 1f)
        assertTrue(button.scaleY < 1f)
        button.render(ColorSaveStatus.SAVED, canSave = false)
        val green = Color.rgb(167, 220, 181)
        advance(436)
        assertNotEquals(green, pixel(button.width / 2, button.height * 3 / 4))
        advance(380)
        assertEquals(green, pixel(button.width / 2, button.height * 3 / 4))
        assertNotEquals(green, pixel(3, button.height / 2))
        capture("reveal")
        assertEquals(buttonBounds, bounds(button))
        assertEquals(siblingBounds, bounds(sibling))
        assertEquals(height, frame.getChildAt(0).height)
        advance(900)
        assertEquals(green, pixel(3, button.height / 2))
        assertEquals(Color.TRANSPARENT, pixel(0, 0))
        capture("saved")
        assertEquals(1f, button.scaleX, 0f)
        assertEquals(1f, button.scaleY, 0f)
        advance(5000)
        assertEquals(green, pixel(3, button.height / 2))
        assertFalse(button.isEnabled)
        assertEquals(button.context.getString(R.string.ui_saved), button.contentDescription)
        button.render(ColorSaveStatus.UNSAVED, canSave = true)
        assertTrue(button.isEnabled)
        assertNotEquals(green, pixel(3, button.height / 2))
        assertEquals(buttonBounds, bounds(button))
    }

    @Test fun slowResultKeepsWaitingAndFailureStopsTheLoopForRetry() {
        button.render(ColorSaveStatus.SAVING, canSave = false)
        val clock = requireNotNull(ReflectionHelpers.getField<ValueAnimator?>(button, "animator"))
        // Robolectric replaces INFINITE with one repeat; restore Android's real loop here.
        ReflectionHelpers.setField(clock, "mRepeatCount", ValueAnimator.INFINITE)
        advance(3200)
        assertTrue(clock.isRunning)
        val green = Color.rgb(167, 220, 181)
        assertNotEquals(green, pixel(3, button.height / 2))
        assertEquals(button.context.getString(R.string.ui_saving), button.contentDescription)
        button.render(ColorSaveStatus.FAILED, canSave = true)
        assertTrue(button.isEnabled)
        assertEquals(button.context.getString(R.string.ui_save_color_swatch), button.contentDescription)
        assertEquals(1f, button.scaleX, 0f)
        button.render(ColorSaveStatus.SAVING, canSave = false)
        advance(1000)
        button.render(ColorSaveStatus.SAVED, canSave = false)
        advance(800)
        assertEquals(green, pixel(3, button.height / 2))
    }

    @Test fun disablingAnimationsAndDetachingBothSettleToTheRealStatus() {
        button.render(ColorSaveStatus.SAVING, canSave = false)
        advance(100)
        setAnimationScale(0f)
        button.render(ColorSaveStatus.SAVED, canSave = false)
        assertEquals(Color.rgb(167, 220, 181), pixel(3, button.height / 2))
        assertEquals(1f, button.scaleX, 0f)
        setAnimationScale(1f)
        button.render(ColorSaveStatus.UNSAVED, canSave = true)
        button.render(ColorSaveStatus.SAVING, canSave = false)
        advance(100)
        (button.parent as LinearLayout).removeView(button)
        assertEquals(1f, button.scaleX, 0f)
        button.render(ColorSaveStatus.SAVED, canSave = false)
        assertEquals(Color.rgb(167, 220, 181), pixel(3, button.height / 2))
    }
}
