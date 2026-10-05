package com.andsi.airlyrics.floating

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.color.AirColorUtils
import com.andsi.airlyrics.core.model.FloatingPosition
import com.andsi.airlyrics.settings.store.FloatingLyricsFontStore
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import kotlin.math.abs

/**
 * Owns the floating lyrics window itself: creation, removal, dragging,
 * style application, lock state and click-through behavior.
 *
 * FloatingLyricsService keeps the media / lyrics state, while this class keeps
 * the Android WindowManager details in one small box.
 */
class FloatingLyricsWindow(
    private val context: Context,
    private val onVisibilityChanged: (Boolean) -> Unit
) {
    private val windowContext = if (Build.VERSION.SDK_INT >= 30) {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        context.createDisplayContext(manager.defaultDisplay)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    } else context
    private val windowManager = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var lyricsView: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var position: FloatingPosition? = null
    private var geometry: FloatingWindowGeometry? = null
    private var gestureActive = false
    private var dragging = false
    private var remapPending = false
    private val refreshLayout = Runnable {
        val remap = remapPending
        remapPending = false
        updateLayout(remap)
    }
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        scheduleLayout()
    }

    fun onConfigurationChanged() {
        gestureActive = false
        scheduleLayout(remap = true)
    }

    private fun scheduleLayout(remap: Boolean = false) {
        val view = lyricsView ?: return
        remapPending = remapPending || remap
        view.removeCallbacks(refreshLayout)
        view.post(refreshLayout)
    }

    private var startX = 0
    private var startY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f

    val textView: TextView?
        get() = lyricsView

    val isVisible: Boolean
        get() = lyricsView != null

    fun show(): Boolean {
        if (!Settings.canDrawOverlays(context)) {
            hideAfterFailure()
            return false
        }

        lyricsView?.let {
            val refreshed = applyStyle()
            if (refreshed) onVisibilityChanged(true)
            return refreshed
        }

        val view = FloatingLyricsTextView(windowContext).apply {
            text = this@FloatingLyricsWindow.context.getString(R.string.ui_waiting_for_media_message)
            includeFontPadding = false
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            windowFlagsForCurrentBehavior(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 30) {
                setFitInsetsTypes(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                setFitInsetsIgnoringVisibility(true)
            }
        }

        view.setOnTouchListener(::handleTouch)

        return runCatching {
            lyricsView = view
            params = layoutParams
            position = FloatingLyricsStyleStore.getRelativePosition(context)
            applyStyle(view)
            calculateLayout(view, layoutParams, remap = true)
            windowManager.addView(view, layoutParams)
            view.onWindowConfigurationChanged = ::onConfigurationChanged
            view.addOnLayoutChangeListener(layoutListener)
            view.setOnApplyWindowInsetsListener { _, insets ->
                scheduleLayout()
                insets
            }
            view.requestApplyInsets()
            onVisibilityChanged(true)
            true
        }.getOrElse {
            hideAfterFailure()
            false
        }
    }

    fun hide(notifyVisibilityChanged: Boolean = true): Boolean {
        val view = lyricsView
        view?.removeCallbacks(refreshLayout)
        view?.removeOnLayoutChangeListener(layoutListener)
        view?.setOnApplyWindowInsetsListener(null)
        (view as? FloatingLyricsTextView)?.onWindowConfigurationChanged = null
        gestureActive = false
        remapPending = false
        geometry = null
        position = null
        val removed = if (view != null) {
            runCatching { windowManager.removeView(view) }.isSuccess
        } else {
            true
        }

        lyricsView = null
        params = null
        if (notifyVisibilityChanged) {
            onVisibilityChanged(false)
        }
        return removed
    }

    fun applyStyle(): Boolean {
        val view = lyricsView ?: return true
        val p = params ?: return true
        return runCatching {
            applyStyle(view)
            if (calculateLayout(view, p, remap = true)) windowManager.updateViewLayout(view, p)
            true
        }.getOrElse {
            hideAfterFailure()
            false
        }
    }

    fun setLocked(locked: Boolean): Boolean {
        val previousLocked = FloatingLyricsStyleStore.isLocked(context)
        FloatingLyricsStyleStore.setLocked(context, locked)
        val updated = updateWindowBehavior()
        if (!updated) FloatingLyricsStyleStore.setLocked(context, previousLocked)
        return updated
    }

    fun setClickThrough(clickThrough: Boolean): Boolean {
        val previousClickThrough = FloatingLyricsStyleStore.isClickThrough(context)
        FloatingLyricsStyleStore.setClickThrough(context, clickThrough)
        val updated = updateWindowBehavior()
        if (!updated) FloatingLyricsStyleStore.setClickThrough(context, previousClickThrough)
        return updated
    }

    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        val p = params ?: return false
        val isLocked = FloatingLyricsStyleStore.isLocked(context)

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureActive = true
                dragging = false
                startX = p.x
                startY = p.y
                touchStartX = event.rawX
                touchStartY = event.rawY
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isLocked || !gestureActive) {
                    true
                } else {
                    if (!dragging && isClick(event)) return true
                    dragging = true
                    val bounds = geometry ?: return true
                    val dx = (event.rawX - touchStartX).toInt() *
                        if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
                    p.x = (startX + dx).coerceIn(0, bounds.travelX)
                    p.y = (startY + (event.rawY - touchStartY).toInt()).coerceIn(0, bounds.travelY)
                    runCatching { windowManager.updateViewLayout(view, p) }
                        .onFailure { hideAfterFailure() }
                        .isSuccess
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!gestureActive) return true
                if (!dragging && isClick(event)) view.performClick()
                if (!isLocked && dragging) saveDraggedPosition(p)
                gestureActive = false
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (gestureActive) scheduleLayout(remap = true)
                gestureActive = false
                true
            }

            else -> true
        }
    }

    private fun isClick(event: MotionEvent): Boolean {
        val threshold = touchSlop.toFloat()
        return abs(event.rawX - touchStartX) <= threshold &&
            abs(event.rawY - touchStartY) <= threshold
    }

    private fun applyStyle(view: TextView) {
        val style = FloatingLyricsStyleStore.getStyle(context)

        view.textSize = style.textSizeSp
        FloatingLyricsFontStore.applyTypeface(
            view,
            style.fontFamily,
            style.fontWeight
        )
        view.setTextColor(style.textColor)
        view.gravity = style.gravity
        view.textAlignment = View.TEXT_ALIGNMENT_GRAVITY
        view.setPadding(
            dp(style.paddingHorizontalDp),
            dp(style.paddingVerticalDp),
            dp(style.paddingHorizontalDp),
            dp(style.paddingVerticalDp)
        )

        if (style.shadowRadius > 0f) {
            view.setShadowLayer(
                style.shadowRadius,
                0f,
                0f,
                AirColorUtils.multiplyAlpha(style.shadowColor, Color.alpha(style.textColor))
            )
        } else {
            view.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }

        view.background = if (style.backgroundEnabled) {
            GradientDrawable().apply {
                cornerRadius = dp(style.cornerRadiusDp).toFloat()
                setColor(AirColorUtils.withAlpha(style.backgroundColor, style.backgroundAlpha))
            }
        } else {
            null
        }
    }

    private fun saveDraggedPosition(p: WindowManager.LayoutParams) {
        val bounds = geometry ?: return
        val previous = position ?: return
        position = bounds.position(p.x, p.y, previous).also {
            FloatingLyricsStyleStore.saveRelativePosition(context, it)
        }
    }

    private fun updateLayout(remap: Boolean) {
        val view = lyricsView ?: return
        val p = params ?: return
        runCatching {
            if (remap) applyStyle(view)
            if (calculateLayout(view, p, remap)) windowManager.updateViewLayout(view, p)
        }.onFailure {
            Log.w("FloatingLyricsWindow", "Unable to update overlay geometry", it)
            hideAfterFailure()
        }
    }

    /** Coordinates are relative to WindowManager's inset-fitted frame, not the physical display. */
    @Suppress("DEPRECATION")
    private fun availableSize(view: View): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return (metrics.bounds.width() - insets.left - insets.right).coerceAtLeast(1) to
                (metrics.bounds.height() - insets.top - insets.bottom).coerceAtLeast(1)
        }
        // Before WindowMetrics, the attached view exposes the display frame fitted by WM.
        // Subtracting rootWindowInsets again would double-count already fitted system bars.
        if (view.isAttachedToWindow) {
            val frame = Rect()
            view.getWindowVisibleDisplayFrame(frame)
            if (!frame.isEmpty) return frame.width() to frame.height()
        }
        val size = Point()
        windowManager.defaultDisplay.getSize(size)
        return size.x.coerceAtLeast(1) to size.y.coerceAtLeast(1)
    }

    private fun calculateLayout(view: TextView, p: WindowManager.LayoutParams, remap: Boolean): Boolean {
        val (availableWidth, availableHeight) = availableSize(view)
        val width = FloatingWindowGeometry.width(
            availableWidth, FloatingLyricsStyleStore.getStyle(context).maxWidthPercent
        )
        if (view.minWidth != width) view.minWidth = width
        if (view.maxWidth != width) view.maxWidth = width
        if (view.maxHeight != availableHeight) view.maxHeight = availableHeight
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(availableHeight, View.MeasureSpec.AT_MOST)
        )
        val next = FloatingWindowGeometry(availableWidth, availableHeight, width, view.measuredHeight)
        val previous = geometry
        val areaChanged = previous == null || previous.availableWidth != availableWidth ||
            previous.availableHeight != availableHeight
        if (areaChanged) gestureActive = false
        val relative = position ?: run {
            val (x, y) = FloatingLyricsStyleStore.getPosition(context)
            val initial = if (FloatingLyricsStyleStore.hasLegacyPosition(context)) {
                next.migrate(x, y, touchSlop)
            } else next.position(0, y, FloatingPosition(0.5f, 0f)).copy(horizontal = 0.5f)
            // Pre-30 must wait for the fitted frame before migrating pixels permanently.
            if (Build.VERSION.SDK_INT >= 30 || view.isAttachedToWindow) {
                position = initial
                FloatingLyricsStyleStore.saveRelativePosition(context, initial)
            }
            initial
        }
        val (x, y) = if (remap || areaChanged || previous.width != width) {
            next.coordinates(relative)
        } else p.x.coerceIn(0, next.travelX) to p.y.coerceIn(0, next.travelY)
        val changed = p.width != width || p.x != x || p.y != y
        p.width = width
        p.x = x
        p.y = y
        geometry = next
        return changed
    }

    private fun updateWindowBehavior(): Boolean {
        val view = lyricsView ?: return true
        val p = params ?: return true
        return runCatching {
            p.flags = windowFlagsForCurrentBehavior()
            windowManager.updateViewLayout(view, p)
            true
        }.getOrElse {
            hideAfterFailure()
            false
        }
    }

    private fun hideAfterFailure() {
        hide(notifyVisibilityChanged = true)
    }

    private fun windowFlagsForCurrentBehavior(): Int {
        return if (FloatingLyricsStyleStore.isClickThrough(context)) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
    }

    private fun dp(value: Int): Int {
        return (value * windowContext.resources.displayMetrics.density).toInt()
    }

}
