package com.andsi.airlyrics.app

import android.graphics.Bitmap
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.platform.AppNightMode
import com.andsi.airlyrics.core.model.ButtonStyle
import com.andsi.airlyrics.core.model.ThemeAccent
import com.andsi.airlyrics.settings.store.ThemeSettingsStore
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.theme.AirLyricsTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsAppearanceMotionInstrumentedTest {
    @Test fun interruptedMotionKeepsMountedViewsAndLastChoice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            AppNightMode.setDark(instrumentation.targetContext, true)
            ThemeSettingsStore.setAccent(instrumentation.targetContext, ThemeAccent.ORANGE)
            ThemeSettingsStore.setButtonStyle(instrumentation.targetContext, ButtonStyle.SOLID)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var original: MainActivity
            lateinit var root: View
            lateinit var panel: View
            scenario.onActivity {
                original = it
                it.graph.viewModel.selectPage(Page.SETTINGS)
                it.graph.uiInvalidator.rebuildCurrentPage(animateContent = false, animateTabs = false)
                root = it.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                panel = it.findViewById(R.id.appearance_picker_panel)
            }
            pause(700)
            style(scenario); pause(110); capture("app-style-opening")
            pause(400); capture("app-style-open")
            accent(scenario); pause(250); capture("app-accent-open")
            scenario.onActivity { a ->
                val label = a.getString(R.string.ui_accent_color_option, a.getString(R.string.ui_blue))
                a.window.decorView.nodes().single { it.contentDescription == label }.performClick()
            }
            pause(200)
            scenario.onActivity { a ->
                assertSame(panel, a.findViewById(R.id.appearance_picker_panel))
                assertEquals(View.VISIBLE, panel.visibility)
                assertEquals(ThemeAccent.BLUE, a.graph.uiHost.themeAccent())
                val label = a.getString(R.string.ui_accent_color_selected, a.getString(R.string.ui_blue))
                a.window.decorView.nodes().single { it.contentDescription == label }.performClick()
            }
            pause(320)
            scenario.onActivity { assertEquals(View.GONE, panel.visibility) }
            style(scenario); pause(360)
            scenario.onActivity { a ->
                a.window.decorView.nodes().filterIsInstance<TextView>()
                    .single { it.text.toString() == a.getString(R.string.ui_button_style_transparent) }.performClick()
            }
            pause(180)
            scenario.onActivity {
                assertEquals(ButtonStyle.TRANSPARENT, it.graph.uiHost.buttonStyle())
                assertEquals(View.VISIBLE, panel.visibility)
            }
            accent(scenario); pause(250)
            var height = 0
            var scroll = 0
            lateinit var settingsScroll: ScrollView
            val sampleReady = CountDownLatch(1)
            var sampledBackground = 0
            scenario.onActivity { a ->
                height = panel.height
                settingsScroll = generateSequence(panel.parent as? View) { it.parent as? View }
                    .filterIsInstance<ScrollView>().first()
                scroll = settingsScroll.scrollY
                a.graph.uiHost.toggleThemeMode()
                // Sample on the UI clock. A later onActivity() may wait for the fade to finish.
                root.postDelayed({
                    sampledBackground = a.graph.uiHost.appearance.frame.palette.background
                    sampleReady.countDown()
                }, 120)
            }
            assertTrue(sampleReady.await(5, TimeUnit.SECONDS))
            scenario.onActivity {
                assertSame(original, it)
                assertNotEquals(AirLyricsTheme.palette(true, ThemeAccent.BLUE).background, sampledBackground)
                assertNotEquals(AirLyricsTheme.palette(false, ThemeAccent.BLUE).background, sampledBackground)
                assertEquals(height, panel.height)
                assertEquals(scroll, settingsScroll.scrollY)
            }
            pause(500); capture("app-light-open")
            val rapidDone = CountDownLatch(1)
            scenario.onActivity { a ->
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                clickAccentNow(a)
                handler.postDelayed({ clickStyleNow(a) }, 45)
                handler.postDelayed({ clickAccentNow(a) }, 85)
                handler.postDelayed({ a.graph.uiHost.toggleThemeMode() }, 120)
                handler.postDelayed({ a.graph.uiHost.toggleThemeMode() }, 195)
                handler.postDelayed({ a.graph.uiHost.toggleThemeMode() }, 265)
                handler.postDelayed({ rapidDone.countDown() }, 850)
            }
            assertTrue(rapidDone.await(5, TimeUnit.SECONDS))
            scenario.onActivity {
                assertSame(original, it)
                assertSame(root, it.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                assertSame(panel, it.findViewById(R.id.appearance_picker_panel))
                assertEquals(View.VISIBLE, panel.visibility)
                assertEquals(height, panel.height)
                assertTrue(it.graph.uiHost.isDarkTheme())
                assertEquals(AirLyricsTheme.palette(true, ThemeAccent.BLUE), it.graph.uiHost.appearance.frame.palette)
                assertEquals("ACCENT", it.graph.uiHost.interactions.read("settings.home.appearanceMenu")?.getString("menu"))
            }
            capture("app-rapid-final")
            scenario.recreate(); pause(500)
            scenario.onActivity {
                assertNotSame(original, it)
                assertEquals(View.VISIBLE, it.findViewById<View>(R.id.appearance_picker_panel).visibility)
                assertEquals(ThemeAccent.BLUE, it.graph.uiHost.themeAccent())
                assertTrue(it.graph.uiHost.isDarkTheme())
            }
        }
    }

    @Test fun captureContourKeyFrames() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.graph.viewModel.selectPage(Page.SETTINGS)
                it.graph.uiInvalidator.rebuildCurrentPage(animateContent = false, animateTabs = false)
            }
            pause(600)
            lateinit var animator: android.animation.ValueAnimator
            scenario.onActivity { a ->
                val panel = a.findViewById<View>(R.id.appearance_picker_panel)
                if (panel.visibility == View.VISIBLE) {
                    a.graph.uiHost.interactions.remove("settings.home.appearanceMenu")
                    a.graph.uiInvalidator.rebuildCurrentPage(animateContent = false, animateTabs = false)
                }
            }
            scenario.onActivity { a ->
                clickStyleNow(a)
                val header = a.findViewById<View>(R.id.appearance_picker_panel).parent
                val field = header.javaClass.getDeclaredField("pickerAnimator").apply { isAccessible = true }
                animator = field.get(header) as android.animation.ValueAnimator
                animator.pause()
            }
            pause(120) // Allow the frame callback to acknowledge pause before seeking.
            for (fraction in listOf(0.15f, 0.35f, 0.55f, 0.72f)) {
                scenario.onActivity { a ->
                    animator.setCurrentFraction(fraction)
                    val header = a.findViewById<View>(R.id.appearance_picker_panel).parent
                    val progress = header.javaClass.getDeclaredField("pickerProgress").apply { isAccessible = true }
                    assertEquals(fraction, progress.getFloat(header), 0.001f)
                }
                pause(500) // Let the software-rendered emulator latch this paused frame.
                capture("app-contour-" + (fraction * 100).toInt())
            }
            scenario.onActivity { animator.resume() }
            pause(400)
            scenario.onActivity { a ->
                a.graph.uiHost.toggleThemeMode()
                val transition = a.graph.uiHost.appearance
                val field = transition.javaClass.getDeclaredField("animator").apply { isAccessible = true }
                animator = field.get(transition) as android.animation.ValueAnimator
                animator.pause()
            }
            pause(120)
            scenario.onActivity { animator.setCurrentFraction(0.3f) }
            pause(150)
            capture("app-theme-midpoint")
            scenario.onActivity { animator.resume() }
            pause(500)
        }
    }

    private fun accent(s: ActivityScenario<MainActivity>) = s.onActivity(::clickAccentNow)
    private fun clickAccentNow(a: MainActivity) {
        val label = a.getString(R.string.ui_choose_accent_color)
        a.window.decorView.nodes().single { it.contentDescription == label }.performClick()
    }
    private fun style(s: ActivityScenario<MainActivity>) = s.onActivity(::clickStyleNow)
    private fun clickStyleNow(a: MainActivity) {
        val res = if (a.graph.uiHost.buttonStyle() == ButtonStyle.SOLID) R.string.ui_button_style_solid else R.string.ui_button_style_transparent
        val label = a.getString(R.string.ui_choose_button_style, a.getString(res))
        a.window.decorView.nodes().single { it.contentDescription == label }.performClick()
    }
    private fun pause(ms: Long) = SystemClock.sleep(ms)
    private fun capture(name: String) {
        val i = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(i.uiAutomation.takeScreenshot())
        val dir = requireNotNull(i.targetContext.getExternalFilesDir("motion-validation"))
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun View.nodes(): List<View> = listOf(this) +
        if (this is ViewGroup) (0 until childCount).flatMap { getChildAt(it).nodes() } else emptyList()
}
