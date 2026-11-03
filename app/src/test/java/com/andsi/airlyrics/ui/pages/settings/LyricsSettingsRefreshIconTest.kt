package com.andsi.airlyrics.ui.pages.settings

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.os.Looper
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.ViewCompat
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsSettingsRefreshIconTest {
    private val normalColor = Color.rgb(241, 143, 169)
    private val successColor = Color.rgb(105, 190, 182)

    @Test fun quickSuccessKeepsLoadingForOneTurnBeforeShowingCheck() = withAnimationScale(1f) {
        withIcon { icon, refresh, check, _ ->
            ShadowChoreographer.setPaused(true)
            ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
            try {
                icon.start()
                icon.complete(true)
                advance(419)
                assertEquals(0f, check.alpha, 0f)

                advance(201)
                assertTrue("check alpha=${check.alpha}", check.alpha > 0.9f)
                assertTrue("refresh alpha=${refresh.alpha}", refresh.alpha < 0.1f)
                assertEquals(successColor, check.imageTintList?.defaultColor)

                advance(630)
                assertTrue("check should remain visible", check.alpha > 0.9f)

                advance(400)
                assertEquals(0f, check.alpha, 0.01f)
                assertEquals(1f, refresh.alpha, 0.01f)
                assertEquals(0f, refresh.rotation, 0.01f)
                assertEquals(normalColor, check.imageTintList?.defaultColor)
            } finally {
                ShadowChoreographer.setPaused(false)
            }
        }
    }

    @Test fun slowReadKeepsSpinningUntilResult() = withAnimationScale(1f) {
        withIcon { icon, refresh, check, _ ->
            ShadowChoreographer.setPaused(true)
            ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
            try {
                icon.start()
                advance(900)
                assertEquals(0f, check.alpha, 0f)
                assertEquals(1f, refresh.alpha, 0f)

                icon.complete(true)
                advance(200)
                assertTrue(check.alpha > 0.9f)
            } finally {
                ShadowChoreographer.setPaused(false)
            }
        }
    }

    @Test fun anotherRefreshDuringCheckHoldCancelsOldRestore() = withAnimationScale(0f) {
        withIcon { icon, refresh, check, _ ->
            icon.start()
            icon.complete(true)
            assertEquals(1f, check.alpha, 0f)

            advance(200)
            icon.start()
            assertEquals(1f, refresh.alpha, 0f)
            icon.complete(true)
            advance(500) // The first refresh would restore here.
            assertEquals(1f, check.alpha, 0f)
            advance(250)
            assertEquals(0f, check.alpha, 0f)
            assertEquals(1f, refresh.alpha, 0f)
        }
    }

    @Test fun failureAndDetachNeverLeaveCheckOrPendingAnimation() = withAnimationScale(0f) {
        withIcon { icon, refresh, check, root ->
            icon.start()
            icon.complete(false)
            assertEquals(0f, check.alpha, 0f)
            assertEquals(1f, refresh.alpha, 0f)
            assertNull(ViewCompat.getStateDescription(icon.view))

            icon.start()
            icon.complete(true)
            root.removeView(icon.view)
            advance(800)
            root.addView(icon.view)
            assertEquals(0f, check.alpha, 0f)
            assertEquals(1f, refresh.alpha, 0f)
            assertEquals(0f, refresh.rotation, 0f)
        }
    }

    private fun withIcon(block: (LyricsSettingsRefreshIcon, ImageView, ImageView, FrameLayout) -> Unit) {
        Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val root = FrameLayout(activity)
            val button = FrameLayout(activity)
            val refresh = ImageView(activity)
            val check = ImageView(activity)
            button.addView(refresh)
            button.addView(check)
            root.addView(button)
            activity.setContentView(root)
            assertTrue(button.isAttachedToWindow)
            block(LyricsSettingsRefreshIcon(button, refresh, check, normalColor, successColor,
                "Refreshing", "Refreshed"), refresh, check, root)
        }
    }

    private fun withAnimationScale(scale: Float, block: () -> Unit) {
        val oldScale = ValueAnimator.getDurationScale()
        try {
            ReflectionHelpers.callStaticMethod<Unit>(ValueAnimator::class.java, "setDurationScale",
                ClassParameter.from(Float::class.javaPrimitiveType, scale))
            block()
        } finally {
            ReflectionHelpers.callStaticMethod<Unit>(ValueAnimator::class.java, "setDurationScale",
                ClassParameter.from(Float::class.javaPrimitiveType, oldScale))
        }
    }

    private fun advance(ms: Long) {
        var remaining = ms
        while (remaining > 0L) {
            val frame = minOf(16L, remaining)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(frame))
            remaining -= frame
        }
    }
}
