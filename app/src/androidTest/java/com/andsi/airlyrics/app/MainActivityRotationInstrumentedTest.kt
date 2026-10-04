package com.andsi.airlyrics.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andsi.airlyrics.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
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
    @Test fun repeatedRealRotationKeepsNavigationReachable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for ((index, orientation) in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE).withIndex()) {
                scenario.onActivity { it.requestedOrientation = orientation }
                val expected = if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
                var matched = false
                val deadline = System.nanoTime() + 10_000_000_000L
                while (!matched && System.nanoTime() < deadline) {
                    instrumentation.waitForIdleSync()
                    scenario.onActivity {
                        val root = it.findViewById<View>(android.R.id.content)
                        matched = it.resources.configuration.orientation == expected && root.width > 0 && root.height > 0 &&
                            !root.isLayoutRequested && (if (expected == Configuration.ORIENTATION_LANDSCAPE) root.width > root.height else root.height > root.width)
                    }
                    if (!matched) Thread.sleep(50)
                }
                assertTrue("Requested orientation was not applied", matched)
                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(android.R.id.content)
                    val target = descendants(root).filterIsInstance<TextView>()
                        .filter { it.text.toString() == activity.getString(R.string.ui_settings) && it.isShown }
                        .firstNotNullOf { text -> generateSequence(text as View) { it.parent as? View }.firstOrNull { it.hasOnClickListeners() } }
                    assertTrue("Settings navigation must have a click listener", target.performClick())
                    assertTrue(root.width > 0 && root.height > 0)
                }
                instrumentation.waitForIdleSync()
                // Surface rotation animations run outside the app's main looper.
                SystemClock.sleep(500)
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                val output = File(instrumentation.targetContext.getExternalFilesDir(null), "rotation-$index.png")
                output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
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
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
                val cases = listOf(Triple(320, 640, 1f), Triple(640, 360, 1f), Triple(1200, 800, 1f), Triple(800, 600, 2f), Triple(360, 640, 1.3f))
                for ((index, dimensions) in cases.withIndex()) {
                    val (width, height, scale) = dimensions
                    shell("wm size ${width}x${height}")
                    shell("settings put system font_scale $scale")
                    awaitLayout(scenario, width, height, scale)
                    scenario.onActivity { activity ->
                        activity.graph.viewModel.selectPage(Page.FLOATING)
                        activity.graph.uiInvalidator.rebuildCurrentPage(animateContent = false, animateTabs = false)
                        if (index == 3) activity.findViewById<View>(android.R.id.content).layoutDirection = View.LAYOUT_DIRECTION_RTL
                    }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(500)
                    scenario.onActivity { activity ->
                        val host = activity.graph.uiHost
                        val root = activity.findViewById<View>(android.R.id.content)
                        host.tabViews.values.forEach { tab ->
                            val visible = android.graphics.Rect()
                            assertTrue("Navigation label must remain reachable", tab.getGlobalVisibleRect(visible))
                            assertTrue(visible.width() > 0 && visible.height() > 0)
                        }
                        assertTrue(root.width > 0 && root.height > 0)
                    }
                    screenshot("window-$index.png")
                }
                lateinit var editor: EditText
                lateinit var editorDialog: Dialog
                scenario.onActivity { activity ->
                    val host = activity.graph.uiHost
                    editorDialog = host.showAirDialog("Editor", positiveText = "Save", body = {
                        addView(EditText(activity).apply {
                            editor = this
                            setText("Long text for keyboard layout ".repeat(15))
                            minLines = 6

                        })
                    })
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    editor.requestFocus()
                    WindowCompat.getInsetsController(editorDialog.window!!, editor).show(WindowInsetsCompat.Type.ime())
                }
                var keyboardVisible = false
                val deadline = System.nanoTime() + 10_000_000_000L
                while (!keyboardVisible && System.nanoTime() < deadline) {
                    SystemClock.sleep(100)
                    instrumentation.waitForIdleSync()
                    scenario.onActivity {
                        keyboardVisible = ViewCompat.getRootWindowInsets(editor)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    }
                }
                assertTrue("The keyboard must actually be visible", keyboardVisible)
                // IME visibility can precede its final inset/layout dispatch.
                SystemClock.sleep(700)
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    // Very short windows scroll the whole form, including its actions.
                    descendants(editorDialog.window!!.decorView).filterIsInstance<androidx.core.widget.NestedScrollView>()
                        .single().apply {
                            isSmoothScrollingEnabled = false
                            fullScroll(View.FOCUS_DOWN)
                        }
                }
                SystemClock.sleep(500)
                scenario.onActivity {
                    val save = descendants(editorDialog.window!!.decorView).filterIsInstance<TextView>().single { it.text.toString() == "Save" }
                    val visible = android.graphics.Rect()
                    assertTrue("Save must remain visible above the keyboard", save.getGlobalVisibleRect(visible))
                    val ime = ViewCompat.getRootWindowInsets(editor)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    assertTrue(visible.bottom <= editorDialog.window!!.decorView.height - ime)
                }
                screenshot("keyboard.png")
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

    private fun awaitLayout(scenario: ActivityScenario<MainActivity>, width: Int, height: Int, scale: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = System.nanoTime() + 10_000_000_000L
        var matched = false
        while (!matched && System.nanoTime() < deadline) {
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val display = it.resources.displayMetrics
                val root = it.findViewById<View>(android.R.id.content)
                matched = display.widthPixels == width && display.heightPixels <= height &&
                    kotlin.math.abs(it.resources.configuration.fontScale - scale) < 0.01f && root.width == width && !root.isLayoutRequested
            }
            if (!matched) SystemClock.sleep(50)
        }
        assertTrue("Window did not settle at ${width}x${height}, font $scale", matched)
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), name)
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun restoreSetting(key: String, value: String) {
        shell(if (value == "null" || value.isBlank()) "settings delete system $key" else "settings put system $key $value")
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
