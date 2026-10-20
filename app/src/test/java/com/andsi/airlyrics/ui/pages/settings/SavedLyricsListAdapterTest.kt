package com.andsi.airlyrics.ui.pages.settings

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.testutil.descendants
import com.andsi.airlyrics.ui.model.LocalLyricsUiItem
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class SavedLyricsListAdapterTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var list: ListView
    private lateinit var adapter: SavedLyricsListAdapter
    private lateinit var root: SavedLyricsDragSelectionLayout
    private val selected = mutableSetOf<String>()
    private var downTime = 0L

    @Before fun setup() {
        ShadowChoreographer.setPaused(true)
        controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val host = controller.get().graph.uiHost
        list = ListView(host).apply { divider = null }
        adapter = SavedLyricsListAdapter(host, { true }, { it in selected }, {
            if (!selected.add(it.selectionKey())) selected.remove(it.selectionKey())
            adapter.refresh(list)
        }, {}).apply { progress = 1f }
        adapter.submit((0 until 2000).map {
            LocalLyricsUiItem("$it.lrc", 0L, 32L, title = "Song $it", indexKey = "$it", displayTitle = "Song $it")
        })
        list.adapter = adapter
        root = SavedLyricsDragSelectionLayout(host, list, { true }, { adapter.attachedItems(list) },
            { selected.toSet() }, { selected.clear(); selected.addAll(it); adapter.refresh(list) },
            { adapter.items.map { it.selectionKey() } }).apply {
            orientation = LinearLayout.VERTICAL
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        controller.get().setContentView(root, ViewGroup.LayoutParams(700, 1000))
        layout()
    }

    @After fun cleanup() { controller.close(); ShadowChoreographer.setPaused(false) }

    private fun layout() {
        root.measure(View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 700, 1000)
        repeat(2) { ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS) }
    }

    @Test fun thousandsOfSongsUseBoundedViewsAndRecycledClicksUseCurrentKeys() {
        assertEquals(2000, adapter.count)
        assertTrue(list.childCount in 1..30)
        val recycled = adapter.getView(0, null, list)
        descendants(recycled).filterIsInstance<CheckBox>().single().performClick()
        assertEquals(setOf("0"), selected)
        assertSame(recycled, adapter.getView(1500, recycled, list))
        val checkbox = descendants(recycled).filterIsInstance<CheckBox>().single()
        assertFalse(checkbox.isChecked)
        checkbox.performClick()
        assertEquals(setOf("0", "1500"), selected)
        list.setSelection(1500)
        layout()
        assertTrue(list.firstVisiblePosition >= 1490)
        assertTrue(list.childCount in 1..30)
        list.setSelection(0)
        layout()
        assertTrue(descendants(list.getChildAt(0)).filterIsInstance<CheckBox>().single().isChecked)
    }

    @Test fun draggingAcrossRecycledRowsIncludesOffscreenSongsAndReverses() {
        val first = list.getChildAt(0)
        touch(MotionEvent.ACTION_DOWN, first.height / 2f)
        ShadowLooper.idleMainLooper(ViewConfiguration.getLongPressTimeout().toLong(), TimeUnit.MILLISECONDS)
        assertEquals(setOf("0"), selected)
        list.setSelection(50)
        layout()
        touch(MotionEvent.ACTION_MOVE, list.getChildAt(0).height / 2f)
        assertEquals((0..50).map(Int::toString).toSet(), selected)
        list.setSelection(5)
        layout()
        touch(MotionEvent.ACTION_MOVE, list.getChildAt(0).height / 2f)
        assertEquals((0..5).map(Int::toString).toSet(), selected)
        touch(MotionEvent.ACTION_UP, list.getChildAt(0).height / 2f)
    }

    @Test fun edgeScrollingSelectsBeyondInitiallyAttachedRowsAndStopsOnRelease() {
        val initialCount = list.childCount
        touch(MotionEvent.ACTION_DOWN, list.getChildAt(0).height / 2f)
        ShadowLooper.idleMainLooper(ViewConfiguration.getLongPressTimeout().toLong(), TimeUnit.MILLISECONDS)
        touch(MotionEvent.ACTION_MOVE, 999f)
        repeat(90) { ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS) }
        assertTrue(list.firstVisiblePosition > 0)
        assertTrue(selected.size > initialCount)
        touch(MotionEvent.ACTION_UP, 999f)
        val snapshot = selected.toSet()
        val position = list.firstVisiblePosition
        repeat(10) { ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS) }
        assertEquals(snapshot, selected)
        assertEquals(position, list.firstVisiblePosition)
    }

    private fun touch(action: Int, y: Float) {
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 30f, y, 0)
        root.dispatchTouchEvent(event)
        event.recycle()
    }
}
