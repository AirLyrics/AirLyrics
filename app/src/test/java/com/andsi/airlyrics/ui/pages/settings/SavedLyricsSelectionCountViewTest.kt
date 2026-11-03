package com.andsi.airlyrics.ui.pages.settings

import android.animation.ValueAnimator
import android.app.Activity
import android.os.Looper
import android.widget.FrameLayout
import java.text.NumberFormat
import java.time.Duration
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import org.robolectric.shadows.ShadowChoreographer

@RunWith(RobolectricTestRunner::class)
class SavedLyricsSelectionCountViewTest {
    @After fun cleanup() { ShadowChoreographer.setPaused(false) }

    @Test fun rapidChangesAndDigitWidthChangesSettleOnLatestCount() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
            val count = countView(controller.get())
            count.setCount(1, animate = true)
            val firstAnimation = animatorOf(count)
            assertNotNull(firstAnimation)
            count.setCount(1, animate = true)
            assertSame(firstAnimation, animatorOf(count))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64))

            count.setCount(10, animate = true)
            count.setCount(9, animate = true)
            count.setCount(99, animate = true)
            count.setCount(100, animate = true)
            assertEquals(100, count.count)
            assertEquals("100", count.text.toString())
            repeat(30) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
            assertNull(animatorOf(count))
            assertEquals("100", count.text.toString())

            count.setCount(9, animate = true)
            count.finishAnimation()
            assertNull(animatorOf(count))
            assertEquals("9", count.text.toString())
        }
    }

    @Test fun disabledSystemAnimationsShowFinalNumberImmediately() {
        Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
            val count = countView(controller.get())
            val oldScale = ValueAnimator.getDurationScale()
            try {
                ReflectionHelpers.callStaticMethod<Unit>(ValueAnimator::class.java, "setDurationScale",
                    ClassParameter.from(Float::class.javaPrimitiveType, 0f))
                count.setCount(10, animate = true)
                assertEquals("10", count.text.toString())
                assertNull(animatorOf(count))
            } finally {
                ReflectionHelpers.callStaticMethod<Unit>(ValueAnimator::class.java, "setDurationScale",
                    ClassParameter.from(Float::class.javaPrimitiveType, oldScale))
            }
        }
    }

    private fun countView(activity: Activity): SavedLyricsSelectionCountView =
        SavedLyricsSelectionCountView(activity, 0, NumberFormat.getIntegerInstance(Locale.US)).also {
            val root = FrameLayout(activity)
            root.addView(it, FrameLayout.LayoutParams(160, 80))
            activity.setContentView(root)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            assertTrue(it.isAttachedToWindow)
        }

    private fun animatorOf(view: SavedLyricsSelectionCountView): ValueAnimator? =
        ReflectionHelpers.getField(view, "animator")
}
