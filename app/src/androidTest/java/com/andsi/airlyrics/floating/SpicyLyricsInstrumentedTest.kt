package com.andsi.airlyrics.floating

import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.core.model.WordByWordAnimationMode
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
@RunWith(AndroidJUnit4::class)
class SpicyLyricsInstrumentedTest {
    @Test fun hardwareFramesAnimateAndPauseWithoutRelayout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var view: FloatingLyricsTextView
            lateinit var renderer: FloatingLyricsRenderer
            scenario.onActivity { activity ->
                view = FloatingLyricsTextView(activity).apply {
                    textSize = 28f
                    includeFontPadding = false
                    maxHeight = activity.resources.displayMetrics.heightPixels
                    gravity = Gravity.CENTER
                    setPadding(40, 40, 40, 40)
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.rgb(25, 30, 40))
                }
                activity.setContentView(view)
                renderer = FloatingLyricsRenderer(textViewProvider = { view },
                    wordByWordLyricsEnabledProvider = { true },
                    wordByWordAnimationModeProvider = { WordByWordAnimationMode.SPICY })
                renderer.updatePlayback(0, false)
                renderer.parseAndShow("", wordByWordLines = listOf(WordByWordLine(0, 6000,
                    "Hello 星光 e\u0301 🎵 A longer held note", listOf(
                        WordByWordSegment("Hello", 0, 450), WordByWordSegment("星光", 450, 1600),
                        WordByWordSegment("e\u0301", 1600, 2100), WordByWordSegment("🎵", 2100, 2600),
                        WordByWordSegment("A longer held note", 2600, 6000)))), emptyText = "")
            }
            instrumentation.waitForIdleSync()
            val initial = screenshot(view, "spicy-start.png")
            lateinit var layout: android.text.Layout
            lateinit var text: CharSequence
            scenario.onActivity { layout = view.layout; text = view.text; renderer.updatePlayback(900, false) }
            instrumentation.waitForIdleSync()
            val middle = screenshot(view, "spicy-middle.png")
            assertFalse(initial.sameAs(middle))
            scenario.onActivity {
                renderer.tick()
                assertSame(text, view.text)
                assertSame(layout, view.layout)
                assertFalse(view.isLayoutRequested)
            }
            instrumentation.waitForIdleSync()
            val paused = screenshot(view, "spicy-paused.png")
            assertTrue(middle.sameAs(paused))
            scenario.onActivity {
                renderer.tick()
                assertFalse("Paused media polling must not invalidate text", view.isDirty)
            }
            scenario.onActivity { renderer.updatePlayback(2900, true) }
            instrumentation.waitForIdleSync()
            val playing = screenshot(view, "spicy-playing.png")
            // Wait for real display frames, with a bounded wait independent of media polling.
            val latch = java.util.concurrent.CountDownLatch(1)
            scenario.onActivity { view.postDelayed({ latch.countDown() }, 350) }
            assertTrue(latch.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val next = screenshot(view, "spicy-next.png")
            assertFalse(playing.sameAs(next))
            scenario.onActivity { view.visibility = View.GONE; renderer.clear() }
            listOf(initial, middle, paused, playing, next).forEach(Bitmap::recycle)
        }
    }

    private fun screenshot(view: FloatingLyricsTextView, name: String): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val location = IntArray(2)
        val committed = java.util.concurrent.CountDownLatch(1)
        var top = 0
        var height = 0
        instrumentation.runOnMainSync {
            view.getLocationOnScreen(location)
            height = (view.layout.height + view.compoundPaddingTop + view.compoundPaddingBottom + 100).coerceAtMost(view.height)
            top = (view.height - height) / 2
            view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            view.invalidate()
        }
        assertTrue("A hardware frame must commit before capture", committed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val screen = instrumentation.uiAutomation.takeScreenshot()
        val crop = Bitmap.createBitmap(screen, location[0], location[1] + top, view.width, height)
        if (screen !== crop) screen.recycle()
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            crop.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return crop
    }
}
