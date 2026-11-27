package com.andsi.airlyrics.app

import com.andsi.airlyrics.testutil.descendants
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.andsi.airlyrics.ui.components.AdaptiveGridLayout
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import java.io.File
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class MainActivityWindowLayoutTest {
    @Config(qualifiers = "en-rUS-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun mainPagesReflowAcrossPhoneTabletAndSplitWindows() = verifyMainPages("en")

    @Config(qualifiers = "zh-rTW-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun mainPagesReflowWithTraditionalChineseLabels() = verifyMainPages("zh-TW")

    private fun verifyMainPages(locale: String) {
        val windows = listOf(
            Window(1240, 775, rail = 104, tiles = 4),
            Window(800, 1024, rail = 0, tiles = 3),
            Window(412, 820, rail = 0, tiles = 2),
            Window(844, 390, rail = 84, tiles = 3),
            Window(550, 720, rail = 0, tiles = 2),
            Window(1500, 900, rail = 104, tiles = 4)
        )
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val graph = activity.graph
            val host = graph.uiHost
            val root = descendants(activity.window.decorView).filterIsInstance<AdaptiveWindowLayout>().single()
            val content = requireNotNull(host.contentContainer)
            host.setFloatingPreviewExpanded(true)
            for ((width, height, rail, tiles) in windows) {
                val size = "${width}x${height}"
                val horizontalInset = if (height == 390) host.dp(18) else 0
                fun layout() {
                    root.setPadding(horizontalInset, host.dp(24), horizontalInset, host.dp(16))
                    layout(root, host.dp(width), host.dp(height))
                }
                fun open(page: Page): View {
                    assertTrue((host.tabViews.getValue(page).parent as View).performClick())
                    ShadowLooper.idleMainLooper()
                    graph.mainHandRenderer.finishPageTransition()
                    layout()
                    return content.getChildAt(0)
                }

                val media = open(Page.MEDIA)
                val nav = root.getChildAt(1)
                if (rail > 0) {
                    assertEquals(size, LinearLayout.VERTICAL, host.tabRow!!.orientation)
                    assertEquals(size, host.dp(rail), nav.width)
                    assertEquals(size, nav.height, (nav as ScrollView).getChildAt(0).height)
                    assertEquals(size, root.paddingTop, nav.top)
                    assertEquals(size, root.height - root.paddingBottom, nav.bottom)
                    assertEquals(size, nav.right, content.left)
                    val slot = host.tabViews.getValue(Page.MEDIA).parent as View
                    val highlight = requireNotNull(host.tabHighlight)
                    val indicator = Bitmap.createBitmap(highlight.width, highlight.height, Bitmap.Config.ARGB_8888)
                    highlight.draw(Canvas(indicator))
                    val center = location(slot)
                    val origin = location(highlight)
                    val pixel = indicator.getPixel(center[0] - origin[0] + slot.width / 2, center[1] - origin[1] + slot.height / 2)
                    indicator.recycle()
                    assertTrue("$size selected tab is outside its highlight", pixel ushr 24 > 0)
                } else {
                    assertEquals(size, LinearLayout.HORIZONTAL, host.tabRow!!.orientation)
                    assertEquals(size, content.bottom, nav.top)
                    assertEquals(size, root.height - root.paddingBottom, nav.bottom)
                }
                val current = anchor(media, "media.current")
                val leading = location(current)
                val titleTop = location(anchor(media, "media.heading"))[1]
                val pageWidth = current.width
                assertEquals(size, minOf(content.width, host.dp(1160)) - host.dp(if (rail == 104) 48 else 40), pageWidth)
                assertEquals(size, pageWidth, anchor(media, "media.heading").width)
                capture(root, locale, size, "media")

                val floating = open(Page.FLOATING)
                val preview = anchor(floating, "floating.preview")
                val grid = anchor(floating, "panel:TEXT_SIZE").parent as AdaptiveGridLayout
                assertEquals(size, leading[0], location(preview)[0])
                assertEquals(size, pageWidth, preview.width)
                assertEquals(size, tiles, grid.columns)
                assertTrue(size, location(grid)[1] > location(preview)[1])
                capture(root, locale, size, "floating")

                val settings = open(Page.SETTINGS)
                val title = descendants(settings).filterIsInstance<TextView>()
                    .single { it.text.toString() == activity.getString(R.string.ui_settings) }
                assertEquals(size, leading[0], location(title)[0])
                assertEquals(size, titleTop.toFloat(), location(title)[1].toFloat(), host.dp(8).toFloat())
                capture(root, locale, size, "settings")
                val lyricsCategory = descendants(settings).filterIsInstance<TextView>()
                    .single { it.text.toString() == activity.getString(R.string.ui_lyrics) }
                val systemCategory = descendants(settings).filterIsInstance<TextView>()
                    .single { it.text.toString() == activity.getString(R.string.ui_system) }
                val category = generateSequence(lyricsCategory as View) { it.parent as? View }.first(View::isClickable)
                if (rail == 104) {
                    assertTrue(size, location(systemCategory)[0] > location(lyricsCategory)[0])
                } else {
                    assertEquals(size, location(lyricsCategory)[0], location(systemCategory)[0])
                    assertTrue(size, location(systemCategory)[1] > location(lyricsCategory)[1])
                }
                assertTrue(category.performClick())
                ShadowLooper.idleMainLooper()
                graph.mainHandRenderer.finishPageTransition()
                layout()
                assertEquals(SettingsSubPage.LYRICS, graph.state.settingsSubPage)
                val lyrics = content.getChildAt(0)
                val heading = anchor(lyrics, "lyrics.settings.heading")
                val currentLyrics = location(anchor(lyrics, "lyrics.settings.current"))
                val recent = location(anchor(lyrics, "lyrics.settings.recent"))
                val strategy = location(anchor(lyrics, "lyrics.settings.search"))
                val directory = location(anchor(lyrics, "lyrics.settings.directory"))
                assertEquals(size, pageWidth, heading.width)
                assertEquals(size, leading[0], currentLyrics[0])
                assertEquals(size, currentLyrics[0], recent[0])
                assertEquals(size, 1, descendants(lyrics).filterIsInstance<ScrollView>().size)
                if (rail == 104) {
                    assertTrue(size, strategy[0] > currentLyrics[0])
                    assertEquals(size, currentLyrics[1], strategy[1])
                    assertTrue(size, recent[1] > currentLyrics[1])
                } else {
                    assertEquals(size, currentLyrics[0], strategy[0])
                    assertTrue(size, strategy[1] > currentLyrics[1] && recent[1] > directory[1])
                }
                capture(root, locale, size, "lyrics")
                activity.onBackPressedDispatcher.onBackPressed()
                ShadowLooper.idleMainLooper()
                assertEquals(SettingsSubPage.HOME, graph.state.settingsSubPage)
            }
        }
    }

    private data class Window(val width: Int, val height: Int, val rail: Int, val tiles: Int)

    private fun anchor(root: View, key: String): View =
        descendants(root).single { it.getTag(R.id.interaction_anchor) == key }

    private fun location(view: View): IntArray = IntArray(2).also(view::getLocationInWindow)

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(exact(width), exact(height))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        view.viewTreeObserver.dispatchOnGlobalLayout()
    }

    private fun capture(view: View, locale: String, size: String, page: String) {
        val directory = System.getenv("AIRLYRICS_LAYOUT_PREVIEW_DIR") ?: return
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(directory, "airlyrics-92-$locale-$size-$page.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun continuousResizeReflowsExistingCardsAndPreservesActivePanel() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            activity.graph.viewModel.selectPage(Page.FLOATING)
            activity.graph.uiInvalidator.rebuildCurrentPage(animateContent = false, animateTabs = false)
            val root = descendants(activity.window.decorView).filterIsInstance<AdaptiveWindowLayout>().single()
            val host = activity.graph.uiHost
            fun resize(width: Int, height: Int) = layout(root, host.dp(width), host.dp(height))
            resize(360, 720)
            val tile = descendants(root).single { it.getTag(R.id.interaction_anchor) == "panel:TEXT_SIZE" }
            tile.performClick()
            val panelId = activity.graph.viewModel.interactions.panel
            val grid = tile.parent as AdaptiveGridLayout
            for ((width, height, columns) in listOf(
                Triple(640, 360, 2), Triple(1000, 600, 3), Triple(1200, 360, 4), Triple(550, 720, 2), Triple(360, 720, 2)
            )) {
                resize(width, height)
                assertSame(tile, anchor(root, "panel:TEXT_SIZE"))
                assertEquals(panelId, activity.graph.viewModel.interactions.panel)
                assertEquals(columns, grid.columns)
            }
        }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun resizingBelowPinnedHeaderKeepsTheSameTextAnchor() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val host = activity.graph.uiHost
            val preview = TextView(activity).apply {
                text = "Preview"
                minimumHeight = host.dp(48)
            }
            val source = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                repeat(12) { index ->
                    addView(TextView(activity).apply {
                        text = "Card $index " + "wrapping content ".repeat(20)
                        textSize = 20f
                    }, LinearLayout.LayoutParams(-1, -2))
                }
            }
            val page = ResponsivePage(
                host, source, "resizeTest",
                pinnedHeader = ResponsivePage.Header(preview, isExpanded = { true })
            )
            activity.setContentView(page)
            fun resize(width: Int) {
                host.windowLayout = com.andsi.airlyrics.ui.layout.WindowLayoutSpec(width.toFloat(), 300f, 1f)
                page.measure(exact(host.dp(width)), exact(host.dp(300)))
                page.layout(0, 0, page.measuredWidth, page.measuredHeight)
                page.viewTreeObserver.dispatchOnGlobalLayout()
            }
            resize(360)
            val scroll = descendants(page).filterIsInstance<ScrollView>().single()
            val anchor = (scroll.getChildAt(0) as ViewGroup).getChildAt(3)
            assertTrue(scroll.paddingTop > 0)
            scroll.scrollTo(0, anchor.top + host.dp(10))
            host.interactionUi.capture()
            val before = host.interactions.read("resizeTest")!!
            assertEquals(anchor.getTag(R.id.interaction_anchor), before.getString("anchor"))
            val previousY = scroll.scrollY
            resize(500)
            host.interactionUi.capture()
            val after = host.interactions.read("resizeTest")!!
            assertEquals(before.getString("anchor"), after.getString("anchor"))
            assertEquals(before.getFloat("offset"), after.getFloat("offset"), 1f)
            assertNotEquals(previousY, scroll.scrollY)
            assertEquals(host.dp(10), scroll.scrollY - anchor.top)
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
}
