package com.andsi.airlyrics.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.andsi.airlyrics.R
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.io.PlatformTestStorageRegistry
import android.graphics.Bitmap
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.components.showAirDialog
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.app.Dialog

@RunWith(AndroidJUnit4::class)
class MainActivityRotationInstrumentedTest {
    @get:Rule val diagnostics = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            runCatching { screenshot("${description.methodName}.png") }
        }
    }

    @Test fun repeatedRealRotationKeepsNavigationReachable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                awaitActivity(scenario) { it.requestedOrientation = orientation }
                val expected = if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
                awaitActivity(
                    scenario,
                    "Requested orientation $expected was not applied",
                    ready = { activity ->
                        val root = activity.findViewById<View>(android.R.id.content)
                        activity.resources.configuration.orientation == expected &&
                            root.width > 0 && root.height > 0 && !root.isLayoutRequested &&
                            (if (expected == Configuration.ORIENTATION_LANDSCAPE) {
                                root.width > root.height
                            } else {
                                root.height > root.width
                            })
                    }
                ) { activity ->
                    activity.graph.viewModel.selectPage(Page.MEDIA)
                    activity.graph.uiInvalidator.rebuildCurrentPage(animateContent = false, animateTabs = false)
                    val root = activity.findViewById<View>(android.R.id.content)
                    val target = descendants(root).filterIsInstance<TextView>()
                        .filter { it.text.toString() == activity.getString(R.string.ui_settings) && it.isShown }
                        .firstNotNullOf { text -> generateSequence(text as View) { it.parent as? View }.firstOrNull { it.hasOnClickListeners() } }
                    assertTrue("Settings navigation must have a click listener", target.performClick())
                    assertEquals(Page.SETTINGS, activity.graph.state.currentPage)
                }
                instrumentation.waitForIdleSync()
                awaitActivity(scenario, "Settings page content did not become usable", ready = { activity ->
                    descendants(activity.window.decorView).filterIsInstance<TextView>().any {
                        it.isShown && it.text.toString() == activity.getString(R.string.ui_lyrics_settings_summary)
                    }
                }) {}
            }
        }
    }

    @Test fun windowSizesLargeTextRtlAndKeyboardKeepControlsWithinTheWindow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val size = shell("wm size").lineSequence().firstOrNull { it.startsWith("Override size:") }?.substringAfter(":")?.trim()
        val density = shell("wm density").lineSequence().firstOrNull { it.startsWith("Override density:") }?.substringAfter(":")?.trim()
        val fontScale = shell("settings get system font_scale").trim()
        val rotation = shell("settings get system user_rotation").trim()
        val autoRotation = shell("settings get system accelerometer_rotation").trim()
        val animationScales = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
            .associateWith { shell("settings get global $it").trim() }
        val hardwareKeyboard = shell("settings get secure show_ime_with_hard_keyboard").trim()
        try {
            animationScales.keys.forEach { shell("settings put global $it 0") }
            shell("settings put secure show_ime_with_hard_keyboard 1")
            shell("settings put system accelerometer_rotation 0")
            shell("settings put system user_rotation 0")
            shell("wm density 160")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitActivity(scenario) { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
                val cases = listOf(Triple(320, 640, 1f), Triple(640, 360, 1f), Triple(1200, 800, 1f), Triple(800, 600, 2f), Triple(360, 640, 1.3f))
                for ((index, dimensions) in cases.withIndex()) {
                    val (width, height, scale) = dimensions
                    shell("wm size ${width}x${height}")
                    shell("settings put system font_scale $scale")
                    awaitLayout(scenario, width, height, scale)
                    awaitActivity(scenario) { activity ->
                        activity.graph.viewModel.selectPage(Page.FLOATING)
                        activity.graph.uiInvalidator.rebuildCurrentPage(animateContent = false, animateTabs = false)
                        if (index == 3) activity.findViewById<View>(android.R.id.content).layoutDirection = View.LAYOUT_DIRECTION_RTL
                    }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(500)
                    awaitActivity(scenario) { activity ->
                        val host = activity.graph.uiHost
                        val root = activity.findViewById<View>(android.R.id.content)
                        host.tabViews.values.forEach { tab ->
                            assertFullyVisible(tab, activity.window.decorView, "Navigation label")
                        }
                        assertTrue(root.width > 0 && root.height > 0)
                    }
                }
                var savedText: String? = null
                lateinit var editor: EditText
                lateinit var editorDialog: Dialog
                awaitActivity(scenario) { activity ->
                    val host = activity.graph.uiHost
                    editorDialog = host.showAirDialog("Editor", positiveText = "Save", onPositive = { savedText = editor.text.toString() }, body = {
                        addView(EditText(activity).apply {
                            editor = this
                            setText("Long text for keyboard layout ".repeat(15))
                            minLines = 6

                        })
                    })
                }
                // A resumed Activity does not imply that the dialog owns window focus yet.
                awaitActivity(scenario, "Editor dialog did not gain window focus", ready = {
                    editor.isAttachedToWindow && editor.isShown && editor.hasWindowFocus() &&
                        editor.width > 0 && editor.height > 0 && !editor.isLayoutRequested
                }) {
                    assertTrue("Editor must accept focus", editor.requestFocus())
                }
                awaitActivity(scenario, "Editor did not establish an input connection", ready = {
                    editor.hasFocus() && editor.hasWindowFocus() &&
                        it.getSystemService(android.view.inputmethod.InputMethodManager::class.java).isActive(editor)
                }) {
                    WindowCompat.getInsetsController(editorDialog.window!!, editor).show(WindowInsetsCompat.Type.ime())
                }
                var lastImeBottom = -1
                var stableSince = 0L
                awaitActivity(scenario, "Keyboard insets did not become visible and settle", ready = {
                    val insets = ViewCompat.getRootWindowInsets(editor)
                    val bottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                    val now = SystemClock.uptimeMillis()
                    if (bottom != lastImeBottom || editorDialog.window!!.decorView.isLayoutRequested) {
                        lastImeBottom = bottom
                        stableSince = now
                    }
                    insets?.isVisible(WindowInsetsCompat.Type.ime()) == true && bottom > 0 &&
                        now - stableSince >= 300
                }) {}
                awaitActivity(scenario) {
                    // Very short windows scroll the whole form, including its actions.
                    descendants(editorDialog.window!!.decorView).filterIsInstance<androidx.core.widget.NestedScrollView>()
                        .single().apply {
                            isSmoothScrollingEnabled = false
                            fullScroll(View.FOCUS_DOWN)
                        }
                }
                SystemClock.sleep(500)
                awaitActivity(scenario) {
                    val save = descendants(editorDialog.window!!.decorView).filterIsInstance<TextView>().single { it.text.toString() == "Save" }
                    assertFullyVisible(save, editorDialog.window!!.decorView, "Save")
                    val expected = editor.text.toString()
                    assertTrue(save.performClick())
                    assertEquals(expected, savedText)
                }
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            }
        } finally {
            animationScales.forEach { (key, value) ->
                shell(if (value == "null") "settings delete global $key" else "settings put global $key $value")
            }
            shell(if (hardwareKeyboard == "null") "settings delete secure show_ime_with_hard_keyboard" else "settings put secure show_ime_with_hard_keyboard $hardwareKeyboard")
            shell("wm size ${size ?: "reset"}")
            shell("wm density ${density ?: "reset"}")
            restoreSetting("font_scale", fontScale)
            restoreSetting("user_rotation", rotation)
            restoreSetting("accelerometer_rotation", autoRotation)
        }
    }

    private fun assertFullyVisible(view: View, decor: View, label: String) {
        try {
            val origin = IntArray(2).also(view::getLocationOnScreen)
            val full = android.graphics.Rect(origin[0], origin[1], origin[0] + view.width, origin[1] + view.height)
            val visible = android.graphics.Rect()
            assertTrue("$label must be visible", view.getLocalVisibleRect(visible))
            assertEquals("$label must not be clipped", android.graphics.Rect(0, 0, view.width, view.height), visible)
            val decorOrigin = IntArray(2).also(decor::getLocationOnScreen)
            val insets = ViewCompat.getRootWindowInsets(decor)?.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()) ?: androidx.core.graphics.Insets.NONE
            val available = android.graphics.Rect(decorOrigin[0] + insets.left, decorOrigin[1] + insets.top,
                decorOrigin[0] + decor.width - insets.right, decorOrigin[1] + decor.height - insets.bottom)
            assertTrue("$label must fit available window $available; actual $full", available.contains(full))
        } catch (failure: AssertionError) {
            runCatching { screenshot("clipped-control.png") }
            throw failure
        }
    }

    private fun awaitLayout(scenario: ActivityScenario<MainActivity>, width: Int, height: Int, scale: Float) {
        awaitActivity(scenario, "Window did not settle at ${width}x${height}, font $scale", ready = {
            val display = it.resources.displayMetrics
            val root = it.findViewById<View>(android.R.id.content)
            display.widthPixels == width && display.heightPixels <= height &&
                kotlin.math.abs(it.resources.configuration.fontScale - scale) < 0.01f &&
                root.width == width && root.height > 0 && !root.isLayoutRequested
        }) {}
    }

    private fun awaitActivity(
        scenario: ActivityScenario<MainActivity>,
        message: String = "MainActivity did not resume",
        ready: (MainActivity) -> Boolean = { true },
        action: (MainActivity) -> Unit
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (SystemClock.uptimeMillis() < deadline) {
            var completed = false
            instrumentation.runOnMainSync {
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                // Rotation temporarily leaves ActivityScenario without a current Activity.
                // Check lifecycle and act in one main-thread turn so destruction cannot
                // occur between the readiness check and onActivity.
                if (monitor.getActivitiesInStage(Stage.RESUMED).none { it is MainActivity }) {
                    return@runOnMainSync
                }
                scenario.onActivity { activity ->
                    if (!activity.isFinishing && !activity.isDestroyed &&
                        monitor.getLifecycleStageOf(activity) == Stage.RESUMED && ready(activity)) {
                        action(activity)
                        completed = true
                    }
                }
            }
            if (completed) return
            SystemClock.sleep(50)
        }
        throw AssertionError(message)
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            PlatformTestStorageRegistry.getInstance().openOutputFile(name).use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun restoreSetting(key: String, value: String) {
        shell(if (value == "null" || value.isBlank()) "settings delete system $key" else "settings put system $key $value")
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
