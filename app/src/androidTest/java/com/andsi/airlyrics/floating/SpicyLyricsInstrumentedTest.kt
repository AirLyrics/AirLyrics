package com.andsi.airlyrics.floating

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import com.andsi.airlyrics.test.ExtendedInstrumentationTest
import com.andsi.airlyrics.test.HardwareFrameTest
import com.andsi.airlyrics.test.InstrumentationFailureDiagnostics
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.core.model.WordByWordAnimationMode
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
@ExtendedInstrumentationTest
@HardwareFrameTest
@RunWith(AndroidJUnit4::class)
class SpicyLyricsInstrumentedTest {
    @get:Rule val diagnostics = InstrumentationFailureDiagnostics()

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
            assertFalse("Paused Spicy position must change the committed frame", initial.sameAs(middle))
            scenario.onActivity {
                renderer.tick()
                assertSame(text, view.text)
                assertSame(layout, view.layout)
                assertFalse(view.isLayoutRequested)
            }
            instrumentation.waitForIdleSync()
            val paused = screenshot(view, "spicy-paused.png")
            assertTrue("Paused polling must preserve the committed frame", middle.sameAs(paused))
            scenario.onActivity {
                renderer.tick()
                assertFalse("Paused media polling must not invalidate text", view.isDirty)
            }
            scenario.onActivity { renderer.updatePlayback(2900, true) }
            instrumentation.waitForIdleSync()
            val playing = screenshot(view, "spicy-playing.png")
            // Wait for real display frames, with a bounded wait independent of media polling.
            val latch = CountDownLatch(1)
            scenario.onActivity { view.postDelayed({ latch.countDown() }, 350) }
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            val next = screenshot(view, "spicy-next.png")
            assertFalse("Playing Spicy position must advance in committed frames", playing.sameAs(next))
            scenario.onActivity { view.visibility = View.GONE; renderer.clear() }
            listOf(initial, middle, paused, playing, next).forEach(Bitmap::recycle)
        }
    }

    private fun screenshot(view: FloatingLyricsTextView, name: String): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var ready = false
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                ready = view.isAttachedToWindow && view.isShown && view.hasWindowFocus() &&
                    view.width > 0 && view.height > 0 && !view.isLayoutRequested
            }
            if (!ready) SystemClock.sleep(50)
        }
        assertTrue("$name: lyric window did not gain focus and finish layout", ready)
        val committed = CountDownLatch(1)
        lateinit var source: Rect
        instrumentation.runOnMainSync {
            check(view.isAttachedToWindow && view.isShown && view.hasWindowFocus()) {
                "$name: lyric window lost focus before capture"
            }
            val location = IntArray(2).also(view::getLocationInWindow)
            val height = (view.layout.height + view.compoundPaddingTop + view.compoundPaddingBottom + 100)
                .coerceAtMost(view.height)
            val top = (view.height - height) / 2
            source = Rect(location[0], location[1] + top, location[0] + view.width,
                location[1] + top + height)
            view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            view.invalidate()
        }
        assertTrue("$name: lyric window did not commit a hardware frame", committed.await(5, TimeUnit.SECONDS))
        val bitmap = Bitmap.createBitmap(source.width(), source.height(), Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        var result = -1
        PixelCopy.request((view.context as Activity).window, source, bitmap, {
            result = it
            copied.countDown()
        }, Handler(Looper.getMainLooper()))
        try {
            assertTrue("$name: PixelCopy did not finish", copied.await(5, TimeUnit.SECONDS))
            assertEquals("$name: PixelCopy failed", PixelCopy.SUCCESS, result)
            PlatformTestStorageRegistry.getInstance().openOutputFile(name).use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            return bitmap
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }
}
