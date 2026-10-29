package com.andsi.airlyrics.test

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.Stage
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Records lifecycle transitions and writes the device and window state on failure. */
class InstrumentationFailureDiagnostics : TestWatcher() {
    private val lifecycle = ArrayDeque<String>()
    private var currentTest: Description? = null
    private var captured = false
    private val callback = ActivityLifecycleCallback { activity, stage ->
        val decor = activity.window?.decorView
        val entry = "${android.os.SystemClock.uptimeMillis()} $stage ${activity.javaClass.simpleName}" +
            "@${System.identityHashCode(activity)} task=${activity.taskId}" +
            " finishing=${activity.isFinishing} destroyed=${activity.isDestroyed}" +
            " orientation=${activity.resources.configuration.orientation}" +
            " windowFocus=${decor?.hasWindowFocus()} attached=${decor?.isAttachedToWindow}"
        synchronized(lifecycle) {
            if (lifecycle.size == 80) lifecycle.removeFirst()
            lifecycle.addLast(entry)
        }
    }

    override fun starting(description: Description) {
        currentTest = description
        captured = false
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)
    }

    override fun finished(description: Description) {
        ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)
    }

    override fun failed(error: Throwable, description: Description) {
        if (!captured) capture(error, description)
    }

    /** Call while the scenario is still open when a wait or input action fails. */
    fun captureNow(error: Throwable) {
        if (!captured) capture(error, currentTest ?: Description.createSuiteDescription("unknown-test"))
    }

    private fun capture(error: Throwable, description: Description) {
        captured = true
        val testName = "${description.className}.${description.methodName}"
        val fileName = testName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val storage = PlatformTestStorageRegistry.getInstance()
        val report = buildString {
            appendLine("Test: $testName")
            appendLine("Failure: ${error.stackTraceToString()}")
            appendLine("Lifecycle timeline:")
            synchronized(lifecycle) { lifecycle.forEach(::appendLine) }
            appendLine("Lifecycle and app windows:")
            appendLine(runCatching { activitySnapshot() }.getOrElse { "Snapshot failed: $it" })
            for (command in listOf(
                "wm size", "wm density", "settings get system font_scale",
                "settings get system user_rotation", "settings get system accelerometer_rotation",
                "cmd window user-rotation",
                "settings get secure show_ime_with_hard_keyboard", "dumpsys window windows",
                "dumpsys activity activities", "dumpsys input_method"
            )) {
                appendLine("\n$ $command")
                appendLine(runCatching { shell(command) }.getOrElse { "Command failed: $it" })
            }
        }
        Log.e("AirLyricsTest", "$testName failed; diagnostics: $fileName.txt", error)
        runCatching { storage.openOutputFile("$fileName.txt").bufferedWriter().use { it.write(report) } }
            .onFailure { Log.e("AirLyricsTest", "Could not save failure diagnostics", it) }
        runCatching {
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (image != null) {
                try {
                    storage.openOutputFile("$fileName.png").use {
                        check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally {
                    image.recycle()
                }
            }
        }.onFailure { Log.e("AirLyricsTest", "Could not save failure screenshot", it) }
    }

    private fun activitySnapshot(): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var snapshot = ""
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            snapshot = buildString {
                for (stage in Stage.values()) {
                    for (activity in monitor.getActivitiesInStage(stage)) {
                        val decor = activity.window?.decorView
                        appendLine(
                            "$stage ${activity.javaClass.name}@${System.identityHashCode(activity)} " +
                                "task=${activity.taskId} finishing=${activity.isFinishing} " +
                                "destroyed=${activity.isDestroyed} orientation=${activity.resources.configuration.orientation} " +
                                "requested=${activity.requestedOrientation}"
                        )
                        if (decor != null) {
                            appendLine(
                                "  decor: focus=${decor.hasWindowFocus()} attached=${decor.isAttachedToWindow} " +
                                    "shown=${decor.isShown} visibility=${decor.visibility == View.VISIBLE} " +
                                    "size=${decor.width}x${decor.height} layoutRequested=${decor.isLayoutRequested}"
                            )
                        }
                    }
                }
            }
        }
        return snapshot.ifBlank { "No monitored activities" }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
}
