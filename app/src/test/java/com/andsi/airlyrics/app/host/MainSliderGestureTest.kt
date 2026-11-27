package com.andsi.airlyrics.app.host

import com.andsi.airlyrics.ui.components.SettingsSliderRow
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import com.andsi.airlyrics.app.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainSliderGestureTest {
    @Test
    fun sliderCapturesDragBeforeVerticalParentAndReleasesOnUpOrCancel() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            var commits = 0
            val row = SettingsSliderRow(activity.graph.uiHost, "Glow spread", "100%")
            row.bindRange(100, 50, 200, 5, { "$it%" }, onPreview = {}, onCommit = { commits++ })
            var disallowed = false
            val scroll = object : ScrollView(activity) {
                override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
                    disallowed = disallow
                    super.requestDisallowInterceptTouchEvent(disallow)
                }
            }.apply { addView(row) }
            activity.setContentView(scroll)
            scroll.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
            scroll.layout(0, 0, 400, 300)
            val slider = row.seekBar
            fun touch(action: Int, x: Float, time: Long) {
                val event = MotionEvent.obtain(0L, time, action, x, slider.height / 2f, 0)
                try { slider.dispatchTouchEvent(event) } finally { event.recycle() }
            }

            touch(MotionEvent.ACTION_DOWN, 100f, 0L)
            assertTrue(disallowed)
            touch(MotionEvent.ACTION_MOVE, 300f, 20L)
            assertTrue(disallowed)
            touch(MotionEvent.ACTION_UP, 300f, 40L)
            assertFalse(disallowed)
            assertEquals("The native SeekBar must still commit the drag once", 1, commits)

            touch(MotionEvent.ACTION_DOWN, 300f, 60L)
            assertTrue(disallowed)
            touch(MotionEvent.ACTION_CANCEL, 300f, 80L)
            assertFalse(disallowed)
        }
    }
}
