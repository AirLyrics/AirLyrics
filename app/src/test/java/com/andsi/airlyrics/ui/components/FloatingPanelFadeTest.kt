package com.andsi.airlyrics.ui.components

import android.app.Activity
import android.transition.TransitionValues
import android.view.View
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FloatingPanelFadeTest {
    private val controller = Robolectric.buildActivity(Activity::class.java)
    private lateinit var root: FrameLayout

    @Before fun setup() {
        controller.setup().visible()
        root = FrameLayout(controller.get())
        controller.get().setContentView(root)
    }

    @After fun cleanup() { controller.close() }

    private fun capture(fade: FloatingPanelFade, view: View, start: Boolean) =
        TransitionValues().apply {
            this.view = view
            if (start) fade.captureStartValues(this) else fade.captureEndValues(this)
        }

    @Test fun removingAChildOfAHiddenPageDoesNotCreateAnOverlayGhost() {
        for (visibility in listOf(View.GONE, View.INVISIBLE)) {
            val hidden = FrameLayout(controller.get()).apply { this.visibility = visibility }
            val child = View(controller.get())
            hidden.addView(child)
            root.addView(hidden)
            assertFalse(child.isShown)
            val fade = FloatingPanelFade()
            val start = capture(fade, child, start = true)
            hidden.removeView(child)
            assertNull(fade.createAnimator(root, start, null))
            assertNull("The removed hidden child must never be reparented into an overlay", child.parent)
            root.removeView(hidden)
        }
    }

    @Test fun addingVisibleChildrenUnderAHiddenPageDoesNotAnimateThem() {
        val hidden = FrameLayout(controller.get()).apply { visibility = View.GONE }
        root.addView(hidden)
        val child = View(controller.get())
        hidden.addView(child)
        val fade = FloatingPanelFade()
        val end = capture(fade, child, start = false)
        assertNull(fade.createAnimator(root, null, end))
        assertSame(hidden, child.parent)
        assertFalse(child.isShown)
    }

    @Test fun aPageThatBecomesVisibleStillFadesIn() {
        val page = FrameLayout(controller.get()).apply { visibility = View.GONE }
        root.addView(page)
        val fade = FloatingPanelFade()
        val start = capture(fade, page, start = true)
        page.visibility = View.VISIBLE
        val end = capture(fade, page, start = false)
        val animator = fade.createAnimator(root, start, end)
        assertNotNull("Real page navigation must retain its fade", animator)
        animator!!.start()
        animator.end()
        assertTrue(page.isShown)
    }
}
