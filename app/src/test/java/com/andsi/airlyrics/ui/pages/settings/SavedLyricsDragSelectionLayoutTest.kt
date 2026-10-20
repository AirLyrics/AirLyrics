package com.andsi.airlyrics.ui.pages.settings

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class SavedLyricsDragSelectionLayoutTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var root: SavedLyricsDragSelectionLayout
    private lateinit var scroll: ScrollView
    private val rows = mutableListOf<SavedLyricsDragItem>()
    private val selected = mutableSetOf<String>()
    private var enabled = true
    private var clicks = 0
    private var downTime = 0L

    @Before fun setup() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        repeat(24) { index ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(CheckBox(activity).apply { setOnClickListener { clicks++ } }, LinearLayout.LayoutParams(60, 80))
                addView(TextView(activity).apply {
                    text = "Song $index"
                    setOnClickListener { clicks++ }
                }, LinearLayout.LayoutParams(0, 80, 1f))
            }
            body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 80))
            rows += SavedLyricsDragItem("$index", row)
        }
        scroll = ScrollView(activity).apply { addView(body) }
        root = SavedLyricsDragSelectionLayout(activity, scroll, { enabled }, { rows }, { selected.toSet() }, { selected.clear(); selected.addAll(it) }).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        activity.setContentView(root, ViewGroup.LayoutParams(360, 400))
        root.measure(exactly(360), exactly(400))
        root.layout(0, 0, 360, 400)
        advance(2)
        assertTrue(root.isAttachedToWindow)
        assertTrue(rows[1].view.getGlobalVisibleRect(android.graphics.Rect()))
    }

    @After fun cleanup() {
        controller.close()
        ShadowChoreographer.setPaused(false)
    }

    @Test fun checkboxHoldSweepsSkippedRowsWithoutTogglingOnRelease() {
        touch(MotionEvent.ACTION_DOWN, 30f, 120f)
        hold()
        assertEquals(setOf("1"), selected)
        touch(MotionEvent.ACTION_MOVE, 30f, 280f)
        assertEquals(setOf("1", "2", "3"), selected)
        touch(MotionEvent.ACTION_MOVE, 30f, 120f)
        touch(MotionEvent.ACTION_UP, 30f, 120f)
        assertEquals(setOf("1"), selected)
        assertEquals(0, clicks)
    }

    @Test fun lyricHoldInvertsRangeIncludingPreviouslySelectedItems() {
        selected += "2"
        touch(MotionEvent.ACTION_DOWN, 240f, 280f)
        hold()
        touch(MotionEvent.ACTION_MOVE, 240f, 40f)
        touch(MotionEvent.ACTION_UP, 240f, 40f)
        assertEquals(setOf("0", "1", "3"), selected)
        assertEquals(0, clicks)
    }

    @Test fun reversingRestoresMixedOriginalStatesAndCrossingAnchorChangesOtherSide() {
        selected.addAll(setOf("1", "2", "9"))
        touch(MotionEvent.ACTION_DOWN, 30f, 120f)
        hold()
        assertEquals(setOf("2", "9"), selected)
        touch(MotionEvent.ACTION_MOVE, 30f, 280f)
        assertEquals(setOf("3", "9"), selected)
        touch(MotionEvent.ACTION_MOVE, 30f, 120f)
        assertEquals(setOf("2", "9"), selected)
        touch(MotionEvent.ACTION_MOVE, 30f, 40f)
        assertEquals(setOf("0", "2", "9"), selected)
        touch(MotionEvent.ACTION_UP, 30f, 40f)
    }

    @Test fun normalSwipeAndTapDoNotStartDragSelection() {
        touch(MotionEvent.ACTION_DOWN, 30f, 120f)
        touch(MotionEvent.ACTION_UP, 30f, 120f)
        advance(2)
        assertEquals(1, clicks)
        assertTrue(selected.isEmpty())
        touch(MotionEvent.ACTION_DOWN, 240f, 280f)
        touch(MotionEvent.ACTION_MOVE, 240f, 100f)
        touch(MotionEvent.ACTION_MOVE, 240f, 40f)
        hold()
        touch(MotionEvent.ACTION_UP, 240f, 40f)
        assertTrue(selected.isEmpty())
        assertTrue(scroll.scrollY > 0)
    }

    @Test fun stationaryEdgeScrollsAndSelectsNewRowsThenStopsOnRelease() {
        touch(MotionEvent.ACTION_DOWN, 240f, 120f)
        hold()
        touch(MotionEvent.ACTION_MOVE, 240f, 399f)
        advance(60)
        val bottomOffset = scroll.scrollY
        assertTrue("offset=$bottomOffset", bottomOffset > 200)
        assertTrue(selected.any { it.toInt() > 5 })
        touch(MotionEvent.ACTION_MOVE, 240f, 0f)
        advance(30)
        assertTrue(scroll.scrollY < bottomOffset)
        touch(MotionEvent.ACTION_UP, 240f, 0f)
        val stoppedAt = scroll.scrollY
        val stoppedSelection = selected.toSet()
        advance(30)
        assertEquals(stoppedAt, scroll.scrollY)
        assertEquals(stoppedSelection, selected)
        assertEquals(0, clicks)
    }

    @Test fun disabledSelectionAndCancelledGesturesCannotSelectOrScroll() {
        enabled = false
        touch(MotionEvent.ACTION_DOWN, 30f, 120f)
        hold()
        touch(MotionEvent.ACTION_UP, 30f, 120f)
        assertTrue(selected.isEmpty())
        enabled = true
        touch(MotionEvent.ACTION_DOWN, 240f, 120f)
        hold()
        touch(MotionEvent.ACTION_MOVE, 240f, 399f)
        advance(5)
        touch(MotionEvent.ACTION_CANCEL, 240f, 399f)
        val stoppedAt = scroll.scrollY
        val stoppedSelection = selected.toSet()
        advance(30)
        assertEquals(stoppedAt, scroll.scrollY)
        assertEquals(stoppedSelection, selected)
    }

    @Test fun filteredRowsAreSkippedAndDataChangeCancelsActiveGesture() {
        rows[2].view.visibility = View.GONE
        root.measure(exactly(360), exactly(400))
        root.layout(0, 0, 360, 400)
        touch(MotionEvent.ACTION_DOWN, 240f, 120f)
        hold()
        touch(MotionEvent.ACTION_MOVE, 240f, 280f)
        assertFalse("2" in selected)
        assertTrue("3" in selected)
        root.cancelSelectionGesture()
        val stoppedSelection = selected.toSet()
        touch(MotionEvent.ACTION_MOVE, 240f, 399f)
        touch(MotionEvent.ACTION_UP, 240f, 399f)
        advance(20)
        assertEquals(stoppedSelection, selected)
        assertEquals(0, clicks)
    }

    @Test fun detachStopsEdgeScrolling() {
        touch(MotionEvent.ACTION_DOWN, 240f, 120f)
        hold()
        touch(MotionEvent.ACTION_MOVE, 240f, 399f)
        advance(5)
        controller.get().setContentView(View(controller.get()))
        val stoppedAt = scroll.scrollY
        val stoppedSelection = selected.toSet()
        advance(30)
        assertEquals(stoppedAt, scroll.scrollY)
        assertEquals(stoppedSelection, selected)
    }

    private fun touch(action: Int, x: Float, y: Float) {
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        root.dispatchTouchEvent(event)
        event.recycle()
    }
    private fun hold() = advance(ViewConfiguration.getLongPressTimeout() / 16 + 2)
    private fun advance(frames: Int) {
        repeat(frames) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
    }
    private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
