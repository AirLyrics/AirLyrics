package com.andsi.airlyrics.app

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.layout.ResponsivePage
import com.andsi.airlyrics.ui.layout.AdaptiveWindowLayout
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.components.showAirDialog
import com.andsi.airlyrics.ui.model.ConfirmationAction
import com.andsi.airlyrics.ui.state.confirmOperation
import com.andsi.airlyrics.ui.pages.settings.showLanguageDialog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class MainActivityWindowLayoutTest {
    @Test fun continuousResizeReflowsExistingCardsAndPreservesActivePanel() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            activity.graph.viewModel.selectPage(Page.FLOATING)
            activity.graph.uiInvalidator.rebuildCurrentPage(animateContent = false, animateTabs = false)
            val root = descendants(activity.window.decorView).filterIsInstance<AdaptiveWindowLayout>().single()
            val page = descendants(root).filterIsInstance<ResponsivePage>().single()
            fun resize(width: Int, height: Int) {
                val density = activity.resources.displayMetrics.density
                root.measure(exact((width * density).toInt()), exact((height * density).toInt()))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                root.viewTreeObserver.dispatchOnGlobalLayout()
            }
            resize(360, 720)
            val tile = descendants(root).single { it.getTag(R.id.interaction_anchor) == "panel:TEXT_SIZE" }
            tile.performClick()
            val panelId = activity.graph.viewModel.interactions.panel
            for (width in listOf(640, 1000, 1200, 599, 360)) {
                resize(width, 360)
                assertSame(tile, descendants(root).single { it.getTag(R.id.interaction_anchor) == "panel:TEXT_SIZE" })
                assertEquals(panelId, activity.graph.viewModel.interactions.panel)
                assertEquals(if (width >= 600) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL, activity.graph.uiHost.tabRow!!.orientation)
                assertEquals(if (width >= 1000) 2 else 1, page.columnCount)
            }
        }
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun resizingWithinSingleColumnKeepsTheSameTextAnchor() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val host = activity.graph.uiHost
            val source = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                repeat(12) { index ->
                    addView(android.widget.TextView(activity).apply {
                        text = "Card $index " + "wrapping content ".repeat(20)
                        textSize = 20f
                    }, LinearLayout.LayoutParams(-1, -2))
                }
            }
            val page = ResponsivePage(host, source, setOf(0), "resizeTest")
            activity.setContentView(page)
            fun resize(width: Int) {
                host.windowLayout = com.andsi.airlyrics.ui.layout.WindowLayoutSpec(width.toFloat(), 300f, 1f)
                page.measure(exact(host.dp(width)), exact(host.dp(300)))
                page.layout(0, 0, page.measuredWidth, page.measuredHeight)
                page.viewTreeObserver.dispatchOnGlobalLayout()
            }
            resize(360)
            val scroll = descendants(page).filterIsInstance<android.widget.ScrollView>().single()
            scroll.scrollTo(0, (scroll.getChildAt(0) as ViewGroup).getChildAt(3).top + host.dp(10))
            host.interactionUi.capture()
            val before = host.interactions.read("resizeTest.single")!!
            assertNotNull(before.getString("anchor"))
            val previousY = scroll.scrollY
            resize(500)
            host.interactionUi.capture()
            val after = host.interactions.read("resizeTest.single")!!
            assertEquals(before.getString("anchor"), after.getString("anchor"))
            assertEquals(before.getFloat("offset"), after.getFloat("offset"), 1f)
            assertNotEquals(previousY, scroll.scrollY)
            assertEquals(1, page.columnCount)
        }
    }

    @Test fun landscapeConfigurationActuallyChangesLayoutAndRestoresConfirmation() {
        RuntimeEnvironment.setQualifiers("w360dp-h720dp-port")
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            controller.get().graph.uiHost.confirmOperation(ConfirmationAction.DeleteAll, "Delete", "All lyrics", "Delete")
            val request = controller.get().graph.viewModel.interactions.read("confirmation")!!.getString("id")
            RuntimeEnvironment.setQualifiers("w1000dp-h600dp-land")
            controller.recreate()
            ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
            val activity = controller.get()
            assertEquals(Configuration.ORIENTATION_LANDSCAPE, activity.resources.configuration.orientation)
            assertEquals(request, activity.graph.viewModel.interactions.read("confirmation")!!.getString("id"))
            assertEquals(request, activity.graph.uiHost.activeConfirmationId)
            assertTrue(ShadowDialog.getLatestDialog().isShowing)
        }
    }

    @Test fun languageDialogRestoresAndUserDismissDoesNotReopenIt() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            showLanguageDialog(controller.get().graph.uiHost)
            val previous = ShadowDialog.getLatestDialog()
            controller.recreate()
            ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
            assertFalse(previous.isShowing)
            assertNotNull(controller.get().graph.viewModel.interactions.read("aux.language"))
            ShadowDialog.getLatestDialog().dismiss()
            ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
            controller.recreate()
            assertNull(controller.get().graph.viewModel.interactions.read("aux.language"))
        }
    }

    @Test fun dialogContentHonorsMaximumWidthInWideWindow() {
        RuntimeEnvironment.setQualifiers("w1200dp-h800dp-land")
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val host = controller.get().graph.uiHost
            val dialog = host.showAirDialog("Title", "Body", maxWidthDp = 560)
            ShadowLooper.idleMainLooper(350, TimeUnit.MILLISECONDS)
            val scroll = descendants(dialog.window!!.decorView).filterIsInstance<androidx.core.widget.NestedScrollView>().first()
            assertTrue(scroll.width <= host.dp(560))
            assertTrue(scroll.width > 0)
        }
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
