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
import org.junit.Test
import org.junit.runner.RunWith
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.components.showAirDialog
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.app.Dialog
import com.andsi.airlyrics.test.ExtendedInstrumentationTest
import com.andsi.airlyrics.test.InstrumentationFailureDiagnostics
import com.andsi.airlyrics.test.SystemStressTest

@ExtendedInstrumentationTest
@SystemStressTest
@RunWith(AndroidJUnit4::class)
class MainActivityRotationInstrumentedTest {
    @get:Rule val diagnostics = InstrumentationFailureDiagnostics()

    @Test fun repeatedRealRotationKeepsNavigationReachable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val taskId = scenarioTaskId(scenario)
            var initialOrientation = Configuration.ORIENTATION_UNDEFINED
            awaitActivity(scenario, taskId) { initialOrientation = it.resources.configuration.orientation }
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                awaitActivity(scenario, taskId) { it.requestedOrientation = orientation }
                val expected = if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
                awaitActivity(
                    scenario, taskId,
                    "Requested orientation $expected was not applied",
                    ready = { activity ->
                        val root = activity.findViewById<View>(android.R.id.content)
                        activity.window.decorView.hasWindowFocus() &&
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
                awaitActivity(scenario, taskId, "Settings page content did not become usable", ready = { activity ->
                    descendants(activity.window.decorView).filterIsInstance<TextView>().any {
                        it.isShown && it.text.toString() == activity.getString(R.string.ui_lyrics_settings_summary)
                    }
                }) {}
            }
            awaitActivity(scenario, taskId) { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            awaitActivity(scenario, taskId, "Initial orientation was not restored", ready = {
                it.resources.configuration.orientation == initialOrientation && it.window.decorView.hasWindowFocus()
            }) {}
        }
    }

    @Test fun windowSizesLargeTextRtlAndKeyboardKeepControlsWithinTheWindow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val size = shell("wm size").lineSequence().firstOrNull { it.startsWith("Override size:") }?.substringAfter(":")?.trim()
        val density = shell("wm density").lineSequence().firstOrNull { it.startsWith("Override density:") }?.substringAfter(":")?.trim()
        val fontScale = shell("settings get system font_scale").trim()
        val rotation = shell("settings get system user_rotation").trim()
        val autoRotation = shell("settings get system accelerometer_rotation").trim()
        val rotationMode = shell("cmd window user-rotation").trim()
        val animationScales = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
            .associateWith { shell("settings get global $it").trim() }
        val hardwareKeyboard = shell("settings get secure show_ime_with_hard_keyboard").trim()
        var testFailure: Throwable? = null
        try {
            animationScales.keys.forEach { shell("settings put global $it 0") }
            shell("settings put secure show_ime_with_hard_keyboard 1")
            shell("cmd window user-rotation lock 0")
            awaitRotationState("0", "0", "lock 0")
            shell("wm density 160")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val taskId = scenarioTaskId(scenario)
                awaitActivity(scenario, taskId) { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
                val cases = listOf(Triple(320, 640, 1f), Triple(640, 360, 1f), Triple(1200, 800, 1f), Triple(800, 600, 2f), Triple(360, 640, 1.3f))
                for ((index, dimensions) in cases.withIndex()) {
                    val (width, height, scale) = dimensions
                    shell("wm size ${width}x${height}")
                    shell("settings put system font_scale $scale")
                    awaitLayout(scenario, taskId, width, height, scale)
                    awaitActivity(scenario, taskId) { activity ->
                        activity.graph.viewModel.selectPage(Page.FLOATING)
                        activity.graph.uiInvalidator.rebuildCurrentPage(animateContent = false, animateTabs = false)
                        if (index == 3) activity.findViewById<View>(android.R.id.content).layoutDirection = View.LAYOUT_DIRECTION_RTL
                    }
                    instrumentation.waitForIdleSync()
                    awaitActivity(scenario, taskId, "Navigation did not finish layout", ready = { activity ->
                        activity.graph.uiHost.tabViews.values.all { tab ->
                            tab.isAttachedToWindow && tab.width > 0 && tab.height > 0 && !tab.isLayoutRequested
                        }
                    }) { activity ->
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
                awaitActivity(scenario, taskId) { activity ->
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
                awaitActivity(scenario, taskId, "Editor dialog did not gain window focus", ready = {
                    editor.isAttachedToWindow && editor.isShown && editor.hasWindowFocus() &&
                        editor.width > 0 && editor.height > 0 && !editor.isLayoutRequested
                }) {
                    assertTrue("Editor must accept focus", editor.requestFocus())
                }
                awaitActivity(scenario, taskId, "Editor did not establish an input connection", ready = {
                    editor.hasFocus() && editor.hasWindowFocus() &&
                        it.getSystemService(android.view.inputmethod.InputMethodManager::class.java).isActive(editor)
                }) {
                    WindowCompat.getInsetsController(editorDialog.window!!, editor).show(WindowInsetsCompat.Type.ime())
                }
                var lastImeBottom = -1
                var stableSince = 0L
                awaitActivity(scenario, taskId, "Keyboard insets did not become visible and settle", ready = {
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
                awaitActivity(scenario, taskId) {
                    // Very short windows scroll the whole form, including its actions.
                    descendants(editorDialog.window!!.decorView).filterIsInstance<androidx.core.widget.NestedScrollView>()
                        .single().apply {
                            isSmoothScrollingEnabled = false
                            fullScroll(View.FOCUS_DOWN)
                        }
                }
                awaitActivity(scenario, taskId, "Save action did not settle inside the keyboard window", ready = {
                    val save = descendants(editorDialog.window!!.decorView)
                        .filterIsInstance<TextView>().single { it.text.toString() == "Save" }
                    save.isShown && save.width > 0 && save.height > 0 && !save.isLayoutRequested &&
                        editorDialog.window!!.decorView.hasWindowFocus()
                }) {
                    val save = descendants(editorDialog.window!!.decorView).filterIsInstance<TextView>().single { it.text.toString() == "Save" }
                    assertFullyVisible(save, editorDialog.window!!.decorView, "Save")
                    val expected = editor.text.toString()
                    assertTrue(save.performClick())
                    assertEquals(expected, savedText)
                }
                awaitActivity(scenario, taskId, "Editor dialog did not dismiss", ready = {
                    !editorDialog.isShowing
                }) {}
            }
        } catch (failure: Throwable) {
            testFailure = failure
            throw failure
        } finally {
            try {
                animationScales.forEach { (key, value) ->
                    shell(if (value == "null") "settings delete global $key" else "settings put global $key $value")
                }
                shell(if (hardwareKeyboard == "null") "settings delete secure show_ime_with_hard_keyboard" else "settings put secure show_ime_with_hard_keyboard $hardwareKeyboard")
                shell("wm size ${size ?: "reset"}")
                shell("wm density ${density ?: "reset"}")
                restoreSetting("font_scale", fontScale)
                restoreRotationState(rotation, rotationMode)
                awaitSystemState(size, density, fontScale, rotation, autoRotation, rotationMode,
                    animationScales, hardwareKeyboard)
            } catch (restoreFailure: Throwable) {
                val original = testFailure
                if (original != null) {
                    original.addSuppressed(restoreFailure)
                    diagnostics.captureNow(original)
                } else {
                    diagnostics.captureNow(restoreFailure)
                    throw restoreFailure
                }
            }
        }
    }

    private fun assertFullyVisible(view: View, decor: View, label: String) {
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
    }

    private fun restoreRotationState(rotation: String, rotationMode: String) {
        check(rotation in listOf("0", "1", "2", "3")) { "Unexpected saved user_rotation: $rotation" }
        if (shell("settings get system user_rotation").trim() != rotation) {
            shell("cmd window user-rotation lock $rotation")
            // Complete this transition before changing the mode. Back-to-back
            // WindowManager commands can apply out of order while it handles
            // the display changes made by this test.
            awaitRotationState(rotation, "0", "lock $rotation")
        }
        if (shell("cmd window user-rotation").trim() != rotationMode) {
            shell("cmd window user-rotation $rotationMode")
        }
    }

    private fun awaitRotationState(rotation: String, autoRotation: String, mode: String) {
        val expected = mapOf("user_rotation" to rotation,
            "accelerometer_rotation" to autoRotation, "rotation_mode" to mode)
        var observed = emptyMap<String, String>()
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (SystemClock.uptimeMillis() < deadline) {
            observed = mapOf(
                "user_rotation" to shell("settings get system user_rotation").trim(),
                "accelerometer_rotation" to shell("settings get system accelerometer_rotation").trim(),
                "rotation_mode" to shell("cmd window user-rotation").trim()
            )
            if (observed == expected) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Rotation mode did not settle; expected=$expected observed=$observed")
    }

    private fun awaitSystemState(
        size: String?, density: String?, fontScale: String, rotation: String,
        autoRotation: String, rotationMode: String, animationScales: Map<String, String>, hardwareKeyboard: String
    ) {
        val expected = mapOf(
            "size" to size, "density" to density, "font_scale" to fontScale,
            "user_rotation" to rotation, "accelerometer_rotation" to autoRotation,
            "rotation_mode" to rotationMode,
            "show_ime_with_hard_keyboard" to hardwareKeyboard
        ) + animationScales
        var observed = emptyMap<String, String?>()
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (SystemClock.uptimeMillis() < deadline) {
            observed = mapOf(
                "size" to shell("wm size").lineSequence().firstOrNull { it.startsWith("Override size:") }
                    ?.substringAfter(":")?.trim(),
                "density" to shell("wm density").lineSequence().firstOrNull { it.startsWith("Override density:") }
                    ?.substringAfter(":")?.trim(),
                "font_scale" to shell("settings get system font_scale").trim(),
                "user_rotation" to shell("settings get system user_rotation").trim(),
                "accelerometer_rotation" to shell("settings get system accelerometer_rotation").trim(),
                "rotation_mode" to shell("cmd window user-rotation").trim(),
                "show_ime_with_hard_keyboard" to shell("settings get secure show_ime_with_hard_keyboard").trim()
            ) + animationScales.keys.associateWith { shell("settings get global $it").trim() }
            if (observed == expected) return
            SystemClock.sleep(50)
        }
        val failure = AssertionError("Device settings were not restored; expected=$expected observed=$observed")
        throw failure
    }

    private fun awaitLayout(scenario: ActivityScenario<MainActivity>, taskId: Int, width: Int, height: Int, scale: Float) {
        awaitActivity(scenario, taskId, "Window did not settle at ${width}x${height}, font $scale", ready = {
            val display = it.resources.displayMetrics
            val root = it.findViewById<View>(android.R.id.content)
            display.widthPixels == width && display.heightPixels <= height &&
                kotlin.math.abs(it.resources.configuration.fontScale - scale) < 0.01f &&
                root.width == width && root.height > 0 && !root.isLayoutRequested
        }) {}
    }

    private fun awaitActivity(
        scenario: ActivityScenario<MainActivity>,
        taskId: Int,
        message: String = "MainActivity did not resume",
        ready: (MainActivity) -> Boolean = { true },
        action: (MainActivity) -> Unit
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (SystemClock.uptimeMillis() < deadline) {
            var completed = false
            try {
                instrumentation.runOnMainSync {
                    val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                    // ActivityScenario.onActivity drains the main looper when called here;
                    // that can process destruction between the lifecycle check and action.
                    val activity = monitor.getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>().singleOrNull { it.taskId == taskId }
                    if (activity != null && !activity.isFinishing && !activity.isDestroyed && ready(activity)) {
                        action(activity)
                        completed = true
                    }
                }
            } catch (failure: Throwable) {
                diagnostics.captureNow(failure)
                throw failure
            }
            if (completed) return
            SystemClock.sleep(50)
        }
        val state = runCatching { scenario.state.toString() }.getOrElse { "transitioning: $it" }
        val failure = AssertionError("$message; scenario=$state, task=$taskId")
        diagnostics.captureNow(failure)
        throw failure
    }

    private fun scenarioTaskId(scenario: ActivityScenario<MainActivity>): Int {
        var taskId = -1
        scenario.onActivity { taskId = it.taskId }
        return taskId
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun restoreSetting(key: String, value: String) {
        shell(if (value == "null" || value.isBlank()) "settings delete system $key" else "settings put system $key $value")
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
