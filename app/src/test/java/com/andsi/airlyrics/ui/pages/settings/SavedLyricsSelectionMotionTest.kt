package com.andsi.airlyrics.ui.pages.settings

import android.app.Activity
import android.os.Looper
import android.view.View
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SavedLyricsSelectionMotionTest {
    @After fun cleanup() { ShadowChoreographer.setPaused(false) }

    @Test fun reversalStartsAtCurrentProgressAndDetachSettlesAtTarget() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
            val owner = View(controller.get())
            controller.get().setContentView(owner)
            repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
            assertTrue(owner.isAttachedToWindow)
            var rendered = 0f
            val motion = SavedLyricsSelectionMotion(owner, 0f) { rendered = it }
            motion.moveTo(1f)
            repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
            assertTrue("progress=$rendered", rendered > 0f && rendered < 1f)
            val before = rendered
            motion.moveTo(0f)
            assertEquals(before, rendered, 0.001f)
            repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
            assertTrue(rendered < before)
            motion.moveTo(1f)
            controller.get().setContentView(View(controller.get()))
            assertEquals(1f, rendered, 0.001f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            assertEquals(1f, rendered, 0.001f)
        }
    }
}
