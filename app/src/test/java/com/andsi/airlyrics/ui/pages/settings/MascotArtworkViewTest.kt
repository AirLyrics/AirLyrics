package com.andsi.airlyrics.ui.pages.settings

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.testutil.awaitUiCondition
import com.andsi.airlyrics.testutil.descendants
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import com.andsi.airlyrics.ui.model.MascotSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class MascotArtworkViewTest {
    @Test fun catFallsAfterReleaseAndOnlyHandDraggedCatDocks() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            activity.graph.viewModel.selectPage(Page.SETTINGS)
            activity.graph.viewModel.openSettingsSubPage(SettingsSubPage.ABOUT)
            awaitUiCondition {
                hasLaidOutLogo(activity)
            }
            val logo = descendants(activity.window.decorView).asSequence().filterIsInstance<InteractiveLogoView>().first()
            val overlay = descendants(activity.window.decorView).asSequence().filterIsInstance<MascotDragOverlay>().first()
            assertTrue(overlay.width > 0)
            val x = logo.width * 0.5f
            val y = logo.height * 0.35f
            send(logo, MotionEvent.ACTION_DOWN, x, y)
            send(logo, MotionEvent.ACTION_MOVE, x + 75f, y - 90f)
            assertTrue(overlay.isShowing)
            assertTrue(overlay.lift > 0f)
            send(logo, MotionEvent.ACTION_UP, x + 75f, y - 90f)
            assertTrue(overlay.isShowing)
            val cloudY = logo.height * 0.72f
            send(logo, MotionEvent.ACTION_DOWN, x, cloudY)
            send(logo, MotionEvent.ACTION_UP, x, cloudY)
            assertTrue(overlay.isShowing)
            val logoLocation = IntArray(2)
            val overlayLocation = IntArray(2)
            logo.getLocationInWindow(logoLocation)
            overlay.getLocationInWindow(overlayLocation)
            val floatingX = (logoLocation[0] - overlayLocation[0]) + x + overlay.dx
            val floatingY = (logoLocation[1] - overlayLocation[1]) + y + overlay.dy
            send(overlay, MotionEvent.ACTION_DOWN, floatingX, floatingY)
            send(overlay, MotionEvent.ACTION_MOVE, floatingX - 30f, floatingY + 20f)
            assertTrue(overlay.isShowing)
            send(overlay, MotionEvent.ACTION_UP, floatingX - 30f, floatingY + 20f)
            ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS)
            assertTrue(overlay.isShowing)

            val catX = (logoLocation[0] - overlayLocation[0]) + x + overlay.dx
            val catY = (logoLocation[1] - overlayLocation[1]) + y + overlay.dy
            assertTrue(send(overlay, MotionEvent.ACTION_DOWN, catX, catY))
            val dockX = catX - overlay.dx
            val dockY = catY - overlay.dy
            send(overlay, MotionEvent.ACTION_MOVE, dockX, dockY)
            assertEquals(0f, overlay.dx, 1f)
            assertEquals(0f, overlay.dy, 1f)
            send(overlay, MotionEvent.ACTION_UP, dockX, dockY)
            ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS)
            assertFalse(overlay.isShowing)
        }
    }

    @Test fun floorDragCompressesWithoutMovingFeetAndReleaseLaunches() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            activity.graph.viewModel.selectPage(Page.SETTINGS)
            activity.graph.viewModel.openSettingsSubPage(SettingsSubPage.ABOUT)
            awaitUiCondition {
                hasLaidOutLogo(activity)
            }
            val logo = descendants(activity.window.decorView).asSequence().filterIsInstance<InteractiveLogoView>().first()
            val overlay = descendants(activity.window.decorView).asSequence().filterIsInstance<MascotDragOverlay>().first()
            val x = logo.width * 0.5f
            val y = logo.height * 0.35f
            send(logo, MotionEvent.ACTION_DOWN, x, y)
            send(logo, MotionEvent.ACTION_MOVE, x + 75f, y - 90f)
            send(logo, MotionEvent.ACTION_UP, x + 75f, y - 90f)
            ShadowLooper.idleMainLooper(3_500, TimeUnit.MILLISECONDS)
            assertTrue(overlay.isGrounded)

            val logoLocation = IntArray(2)
            val overlayLocation = IntArray(2)
            logo.getLocationInWindow(logoLocation)
            overlay.getLocationInWindow(overlayLocation)
            val catX = (logoLocation[0] - overlayLocation[0]) + x + overlay.dx
            val catY = (logoLocation[1] - overlayLocation[1]) + y + overlay.dy
            val floorY = overlay.dy
            val compressionDrag = logo.height * 0.36f
            assertTrue(send(overlay, MotionEvent.ACTION_DOWN, catX, catY))
            send(overlay, MotionEvent.ACTION_MOVE, catX, catY + compressionDrag)
            assertTrue(overlay.compression > 0.5f)
            assertEquals(floorY, overlay.dy, 0.1f)
            send(overlay, MotionEvent.ACTION_CANCEL, catX, catY + compressionDrag)
            assertTrue(overlay.isGrounded)
            assertEquals(0f, overlay.compression, 0f)
            assertTrue(send(overlay, MotionEvent.ACTION_DOWN, catX, catY))
            send(overlay, MotionEvent.ACTION_MOVE, catX, catY + compressionDrag)
            send(overlay, MotionEvent.ACTION_UP, catX, catY + compressionDrag)
            assertFalse(overlay.isGrounded)
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            assertTrue(overlay.isShowing)
        }
    }

    @Test fun cloudDragCannotLiftCat() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            activity.graph.viewModel.selectPage(Page.SETTINGS)
            activity.graph.viewModel.openSettingsSubPage(SettingsSubPage.ABOUT)
            awaitUiCondition {
                hasLaidOutLogo(activity)
            }
            val logo = descendants(activity.window.decorView).asSequence().filterIsInstance<InteractiveLogoView>().first()
            val overlay = descendants(activity.window.decorView).asSequence().filterIsInstance<MascotDragOverlay>().first()
            val x = logo.width * 0.5f
            val y = logo.height * 0.72f
            send(logo, MotionEvent.ACTION_DOWN, x, y)
            send(logo, MotionEvent.ACTION_MOVE, x + 75f, y - 50f)
            assertFalse(overlay.isShowing)
            send(logo, MotionEvent.ACTION_CANCEL, x + 75f, y - 50f)
            assertFalse(overlay.isShowing)
        }
    }

    @Test fun groundedCatSurvivesLeavingAboutAndSettings() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val viewModel = activity.graph.viewModel
            viewModel.selectPage(Page.SETTINGS)
            viewModel.openSettingsSubPage(SettingsSubPage.ABOUT)
            awaitUiCondition {
                hasLaidOutLogo(activity)
            }
            val logo = descendants(activity.window.decorView).asSequence().filterIsInstance<InteractiveLogoView>().first()
            val overlay = descendants(activity.window.decorView).asSequence().filterIsInstance<MascotDragOverlay>().first()
            val x = logo.width * 0.5f
            val y = logo.height * 0.35f
            send(logo, MotionEvent.ACTION_DOWN, x, y)
            send(logo, MotionEvent.ACTION_MOVE, x + 75f, y - 90f)
            send(logo, MotionEvent.ACTION_UP, x + 75f, y - 90f)
            ShadowLooper.idleMainLooper(3_500, TimeUnit.MILLISECONDS)
            assertTrue(overlay.isGrounded)
            val savedX = viewModel.mascotSession.centreXFraction

            viewModel.selectPage(Page.MEDIA)
            ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS)
            viewModel.selectPage(Page.SETTINGS)
            viewModel.openSettingsSubPage(SettingsSubPage.ABOUT)
            awaitUiCondition {
                descendants(activity.window.decorView).asSequence()
                    .filterIsInstance<MascotDragOverlay>()
                    .any { (it !== overlay) && it.isShowing && (it.width > 0) }
            }
            val restored = descendants(activity.window.decorView).asSequence()
                .filterIsInstance<MascotDragOverlay>().first { (it !== overlay) && it.isShowing }
            assertTrue(restored.isGrounded)
            assertEquals(savedX, viewModel.mascotSession.centreXFraction, 0.05f)
        }
    }

    @Test fun githubDropAbsorbsCatAndKeepsItGoneAcrossNavigation() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val viewModel = activity.graph.viewModel
            viewModel.selectPage(Page.SETTINGS)
            viewModel.openSettingsSubPage(SettingsSubPage.ABOUT)
            awaitUiCondition {
                hasLaidOutLogo(activity)
            }
            val logo = descendants(activity.window.decorView).asSequence().filterIsInstance<InteractiveLogoView>().first()
            val overlay = descendants(activity.window.decorView).asSequence().filterIsInstance<MascotDragOverlay>().first()
            val githubIcon = descendants(activity.window.decorView).first { it.contentDescription == "GitHub" }
            val github = githubIcon.parent as View
            val x = logo.width * 0.5f
            val y = logo.height * 0.35f
            send(logo, MotionEvent.ACTION_DOWN, x, y)
            send(logo, MotionEvent.ACTION_MOVE, x + 75f, y - 90f)
            assertTrue(overlay.isShowing)
            // Robolectric's short viewport puts the GitHub button below the fold.
            val scroll = descendants(activity.window.decorView).asSequence()
                .filterIsInstance<android.widget.ScrollView>()
                .first { page -> descendants(page).any { it === github } }
            scroll.scrollBy(0, 450)
            val logoLocation = IntArray(2)
            val githubLocation = IntArray(2)
            logo.getLocationInWindow(logoLocation)
            github.getLocationInWindow(githubLocation)
            val density = activity.resources.displayMetrics.density
            val inset = 8f * density
            val artSize = logo.width - (2f * inset)
            val githubCenterX = githubLocation[0] + (github.width * 0.5f)
            val githubCenterY = githubLocation[1] + (github.height * 0.5f)
            val logoFaceX = logoLocation[0] + (logo.width * 0.5f)
            val logoFaceY = logoLocation[1] + inset + (artSize * (232f / 512f))
            val dx = githubCenterX - logoFaceX
            val dy = githubCenterY - logoFaceY
            overlay.update(dx, dy, 1f, fromTouch = true)
            ShadowLooper.idleMainLooper(120, TimeUnit.MILLISECONDS)
            assertTrue(
                "GitHub hover missed: requested=($dx,$dy), actual=(${overlay.dx},${overlay.dy})",
                overlay.isGithubHovered,
            )
            send(logo, MotionEvent.ACTION_UP, x + 75f, y - 90f)
            assertEquals(MascotSession.Mode.ABSORBED, viewModel.mascotSession.mode)
            ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS)
            assertFalse(overlay.isShowing)
            assertTrue(overlay.isCatAbsent)

            viewModel.selectPage(Page.MEDIA)
            ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS)
            viewModel.selectPage(Page.SETTINGS)
            viewModel.openSettingsSubPage(SettingsSubPage.ABOUT)
            awaitUiCondition {
                descendants(activity.window.decorView).asSequence()
                    .filterIsInstance<MascotDragOverlay>()
                    .any { (it !== overlay) && (it.width > 0) }
            }
            val restored = descendants(activity.window.decorView).asSequence()
                .filterIsInstance<MascotDragOverlay>().first { (it !== overlay) && (it.width > 0) }
            assertFalse(restored.isShowing)
            assertTrue(restored.isCatAbsent)
        }
    }

    private fun hasLaidOutLogo(activity: MainActivity): Boolean =
        descendants(activity.window.decorView).asSequence()
            .filterIsInstance<InteractiveLogoView>()
            .any { it.width > 0 }

    private fun send(view: View, action: Int, x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, action, x, y, 0).let {
            val consumed = view.dispatchTouchEvent(it)
            it.recycle()
            consumed
        }
    }
}
