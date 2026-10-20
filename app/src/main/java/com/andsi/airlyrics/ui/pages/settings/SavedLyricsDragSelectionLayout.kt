package com.andsi.airlyrics.ui.pages.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.AbsListView
import androidx.core.view.isVisible
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class SavedLyricsDragItem(val key: String, val view: View)

/** Observes ordinary touches, claiming the stream only after a long press in selection mode. */
@SuppressLint("ViewConstructor")
internal class SavedLyricsDragSelectionLayout(
    context: Context,
    private val scroll: View,
    private val selectionEnabled: () -> Boolean,
    private val items: () -> List<SavedLyricsDragItem>,
    private val selectedKeys: () -> Set<String>,
    private val select: (Set<String>) -> Unit,
    private val itemKeys: () -> List<String> = { items().filter { it.view.isVisible }.map { it.key } }
) : LinearLayout(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density
    private val location = IntArray(2)
    private val bounds = Rect()
    private var downX = 0f
    private var downY = 0f
    private var pointerY = 0f
    private var downTime = 0L
    private var armed = false
    private var dragging = false
    private var claimed = false
    private var candidates = emptyList<String>()
    private var positions = emptyMap<String, Int>()
    private var initialSelection = emptySet<String>()
    private var anchorIndex = -1
    private var lastIndex = -1
    private var framePosted = false
    private var lastFrameTime = 0L
    private var scrollRemainder = 0f

    private val activate = Runnable {
        if (armed && selectionEnabled() && isAttachedToWindow) {
            armed = false
            dragging = true
            claimed = true
            // Cancel the original checkbox/row/text gesture so releasing cannot toggle it again.
            val cancel = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, downX, downY, 0)
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            parent?.requestDisallowInterceptTouchEvent(true)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            initialSelection = selectedKeys().toSet()
            lastIndex = -1
            selectCrossedRows()
            scheduleScroll()
        }
    }

    private val scrollFrame = object : Runnable {
        override fun run() {
            framePosted = false
            if (!dragging || !selectionEnabled()) {
                cancelSelectionGesture()
                return
            }
            val speed = edgeVelocity()
            if (speed == 0f || !scroll.canScrollVertically(if (speed > 0f) 1 else -1)) return
            val now = SystemClock.uptimeMillis()
            val seconds = (now - lastFrameTime).coerceIn(1L, 32L) / 1000f
            lastFrameTime = now
            scrollRemainder += speed * seconds
            val delta = scrollRemainder.toInt()
            scrollRemainder -= delta
            if (scroll is AbsListView) scroll.scrollListBy(delta) else scroll.scrollBy(0, delta)
            selectCrossedRows()
            postFrame()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelSelectionGesture()
                claimed = false
                if (selectionEnabled()) {
                    candidates = itemKeys()
                    positions = candidates.withIndex().associate { it.value to it.index }
                    getLocationOnScreen(location)
                    val screenX = location[0] + event.x
                    val screenY = location[1] + event.y
                    val anchor = items().firstOrNull {
                        it.view.getGlobalVisibleRect(bounds) && bounds.contains(screenX.toInt(), screenY.toInt())
                    }
                    anchorIndex = anchor?.let { positions[it.key] } ?: -1
                    if (anchorIndex >= 0) {
                        downX = event.x
                        downY = event.y
                        pointerY = event.y
                        downTime = event.downTime
                        armed = true
                        postDelayed(activate, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                pointerY = event.y
                if (dragging) {
                    if (!selectionEnabled()) cancelSelectionGesture()
                    else {
                        selectCrossedRows()
                        scheduleScroll()
                    }
                } else if (armed && (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)) {
                    cancelSelectionGesture()
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> cancelSelectionGesture()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val consume = claimed
                if (dragging && event.actionMasked == MotionEvent.ACTION_UP) {
                    pointerY = event.y
                    selectCrossedRows()
                }
                cancelSelectionGesture()
                claimed = false
                return if (consume) true else super.dispatchTouchEvent(event)
            }
        }
        return if (claimed) true else super.dispatchTouchEvent(event)
    }

    /** Called when filtering, reloading, leaving selection mode, or detaching the page. */
    fun cancelSelectionGesture() {
        removeCallbacks(activate)
        removeCallbacks(scrollFrame)
        framePosted = false
        armed = false
        dragging = false
        candidates = emptyList()
        positions = emptyMap()
        initialSelection = emptySet()
        anchorIndex = -1
        lastIndex = -1
        scrollRemainder = 0f
        parent?.requestDisallowInterceptTouchEvent(false)
        // Keep a claimed stream consumed until UP/CANCEL, even if the mode changed mid-gesture.
    }

    override fun onDetachedFromWindow() {
        cancelSelectionGesture()
        claimed = false
        super.onDetachedFromWindow()
    }

    private fun viewportY(): Float {
        getLocationOnScreen(location)
        val screenY = location[1] + pointerY
        scroll.getLocationOnScreen(location)
        return screenY - location[1]
    }

    private fun selectCrossedRows() {
        if (anchorIndex !in candidates.indices) return
        val y = viewportY().coerceIn(0f, max(0, scroll.height - 1).toFloat())
        scroll.getLocationOnScreen(location)
        val contentOrigin = location[1]
        // Resolve gaps to the nearest row; a stationary finger never toggles repeatedly.
        // Re-query attached views after every scroll: recycled holders no longer represent the
        // songs they held at DOWN. The range itself is indexed in the complete filtered dataset.
        val nearest = items().filter { it.view.isVisible }.minByOrNull { candidate ->
            val view = candidate.view
            view.getLocationOnScreen(location)
            val top = location[1] - contentOrigin
            when {
                y < top -> top - y
                y > top + view.height -> y - top - view.height
                else -> 0f
            }
        } ?: return
        val index = positions[nearest.key] ?: return
        if (index == lastIndex) return
        lastIndex = index
        val result = initialSelection.toMutableSet()
        for (position in min(anchorIndex, index)..max(anchorIndex, index)) {
            val key = candidates[position]
            if (!result.add(key)) result.remove(key)
        }
        select(result)
    }

    private fun edgeVelocity(): Float {
        val edge = min(56f * density, scroll.height / 3f)
        if (edge <= 0f) return 0f
        val y = viewportY()
        val amount = when {
            y < edge -> -((edge - y) / edge).coerceAtMost(1f)
            y > scroll.height - edge -> ((y - scroll.height + edge) / edge).coerceAtMost(1f)
            else -> 0f
        }
        return amount * abs(amount) * 900f * density
    }

    private fun scheduleScroll() {
        if (!framePosted) {
            lastFrameTime = SystemClock.uptimeMillis()
            if (edgeVelocity() != 0f) postFrame()
        }
    }

    private fun postFrame() {
        framePosted = true
        postOnAnimation(scrollFrame)
    }
}
