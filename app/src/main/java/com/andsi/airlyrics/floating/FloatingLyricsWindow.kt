package com.andsi.airlyrics.floating

import android.app.Dialog
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
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.color.AirColorUtils
import com.andsi.airlyrics.core.model.FloatingPosition
import com.andsi.airlyrics.settings.store.FloatingLyricsFontStore
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import kotlin.math.abs
import kotlin.math.roundToInt

class FloatingLyricsWindow internal constructor(
    private val context: Context,
    private val windowAreaProvider: (() -> FloatingWindowArea)?,
    private val onVisibilityChanged: (Boolean) -> Unit,
) {
    constructor(context: Context, onVisibilityChanged: (Boolean) -> Unit) :
        this(context, null, onVisibilityChanged)

    private val windowContext = if (Build.VERSION.SDK_INT >= 30) {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        context.createDisplayContext(manager.defaultDisplay)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    } else context
    private val windowManager = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var lyricsView: TextView? = null
    private var overlayDialog: Dialog? = null
    private var params: WindowManager.LayoutParams? = null
    private var position: FloatingPosition? = null
    private var positionUsesLegacyBasis = false
    private var geometry: FloatingWindowGeometry? = null
    private var gestureActive = false
    private var dragging = false
    private var layoutRefreshPosted = false
    private var remapPending = false
    private var areaRefreshPending = false
    private var remeasurePending = false
    private var dragRelayoutPending = false
    private var dragInsetsRefreshPosted = false
    private val refreshLayout = Runnable {
        val remap = remapPending
        val refreshArea = areaRefreshPending
        val remeasure = remeasurePending
        layoutRefreshPosted = false
        remapPending = false
        areaRefreshPending = false
        remeasurePending = false
        updateLayout(remap, refreshArea, remeasure)
    }
    private val refreshDragInsets = Runnable {
        dragInsetsRefreshPosted = false
        scheduleLayout(refreshArea = true)
    }
    private val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
            scheduleLayout(refreshArea = Build.VERSION.SDK_INT < 30, remeasure = true)
        }
    }

    fun onConfigurationChanged() {
        gestureActive = false
        dragRelayoutPending = false
        scheduleLayout(remap = true, refreshArea = true)
    }

    private fun onInsetsApplied() {
        val mayBeFromDrag = gestureActive && (dragRelayoutPending || dragInsetsRefreshPosted)
        dragRelayoutPending = false
        if (!mayBeFromDrag) {
            scheduleLayout(refreshArea = true)
        } else if (!areaRefreshPending && !dragInsetsRefreshPosted) {
            // A move and a display inset change can share one dispatch, and local insets can clip the change.
            dragInsetsRefreshPosted = lyricsView?.postDelayed(refreshDragInsets, DRAG_INSETS_REFRESH_DELAY_MS) == true
        }
    }

    private fun scheduleLayout(
        remap: Boolean = false,
        refreshArea: Boolean = false,
        remeasure: Boolean = false,
    ) {
        val view = lyricsView ?: return
        remapPending = remapPending || remap
        areaRefreshPending = areaRefreshPending || refreshArea
        remeasurePending = remeasurePending || remeasure
        if (refreshArea && dragInsetsRefreshPosted) {
            view.removeCallbacks(refreshDragInsets)
            dragInsetsRefreshPosted = false
        }
        if (!layoutRefreshPosted) layoutRefreshPosted = view.post(refreshLayout)
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

        // A Window is required for public, rounded background blur. Blur-behind on
        // LayoutParams would blur the entire screen rather than just the bubble.
        val dialog = if (Build.VERSION.SDK_INT >= 31) {
            Dialog(windowContext, R.style.Theme_AirLyrics_FloatingOverlay).apply {
                setCancelable(false)
                setCanceledOnTouchOutside(false)
            }
        } else null
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
                setFitInsetsIgnoringVisibility(true)
            }
        }

        view.setOnTouchListener(::handleTouch)

        return runCatching {
            lyricsView = view
            overlayDialog = dialog
            position = FloatingLyricsStyleStore.getRelativePosition(context)
            positionUsesLegacyBasis = position != null && FloatingLyricsStyleStore.usesLegacyVerticalPosition(context)
            if (dialog != null) {
                val window = requireNotNull(dialog.window)
                window.attributes = layoutParams
                val attributes = window.attributes
                params = attributes
                dialog.setContentView(
                    view,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                applyStyle(view)
                window.decorView.setPadding(0, 0, 0, 0)
                calculateLayout(view, attributes, remap = true)
                dialog.show()
            } else {
                params = layoutParams
                applyStyle(view)
                calculateLayout(view, layoutParams, remap = true)
                windowManager.addView(view, layoutParams)
            }
            view.onWindowConfigurationChanged = ::onConfigurationChanged
            view.addOnLayoutChangeListener(layoutListener)
            val root = view.rootView
            root.setOnApplyWindowInsetsListener { windowView, insets ->
                onInsetsApplied()
                windowView.onApplyWindowInsets(insets)
            }
            root.requestApplyInsets()
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
        view?.removeCallbacks(refreshDragInsets)
        view?.removeOnLayoutChangeListener(layoutListener)
        view?.rootView?.setOnApplyWindowInsetsListener(null)
        (view as? FloatingLyricsTextView)?.onWindowConfigurationChanged = null
        gestureActive = false
        dragRelayoutPending = false
        dragInsetsRefreshPosted = false
        layoutRefreshPosted = false
        remapPending = false
        areaRefreshPending = false
        remeasurePending = false
        geometry = null
        position = null
        positionUsesLegacyBasis = false
        val removed = runCatching {
            val dialog = overlayDialog
            if (dialog != null) dialog.dismiss()
            else if (view != null) windowManager.removeView(view)
        }.isSuccess

        overlayDialog = null
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
            if (calculateLayout(view, p, remap = true)) windowManager.updateViewLayout(view.rootView, p)
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
                dragRelayoutPending = false
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
                    val x = bounds.clampX(startX + dx)
                    val y = bounds.clampY(startY + (event.rawY - touchStartY).toInt())
                    if (p.x == x && p.y == y) return true
                    p.x = x
                    p.y = y
                    dragRelayoutPending = true
                    runCatching { windowManager.updateViewLayout(view.rootView, p) }
                        .onFailure { hideAfterFailure() }
                        .isSuccess
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!gestureActive) return true
                if (!dragging && isClick(event)) {
                    val textView = view as? TextView
                    val layout = textView?.layout
                    val text = textView?.text as? android.text.Spanned
                    val x = event.x - (textView?.totalPaddingLeft ?: 0) + (textView?.scrollX ?: 0)
                    val y = event.y - (textView?.totalPaddingTop ?: 0) + (textView?.scrollY ?: 0)
                    val link = if (!isLocked && layout != null && text != null && y >= 0 && y < layout.height) {
                        val line = layout.getLineForVertical(y.toInt())
                        if (x >= layout.getLineLeft(line) && x <= layout.getLineRight(line)) {
                            val offset = layout.getOffsetForHorizontal(line, x)
                            text.getSpans(offset, offset, android.text.style.URLSpan::class.java).firstOrNull()
                        } else null
                    } else null
                    if (link != null && link.url.startsWith("https://")) {
                        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link.url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    } else view.performClick()
                }
                if (!isLocked && dragging) saveDraggedPosition(p)
                gestureActive = false
                if (dragInsetsRefreshPosted) scheduleLayout(refreshArea = true)
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (gestureActive) scheduleLayout(remap = true, refreshArea = true)
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
            style.fontWeight,
            style.customFontId
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

        val background = if (style.backgroundEnabled) {
            GradientDrawable().apply {
                cornerRadius = dp(style.cornerRadiusDp).toFloat()
                setColor(AirColorUtils.withAlpha(style.backgroundColor, style.backgroundAlpha))
            }
        } else null
        val window = overlayDialog?.window
        if (window == null) {
            view.background = background
            return
        }
        window.setBackgroundDrawable(background ?: Color.TRANSPARENT.toDrawable())
        if (Build.VERSION.SDK_INT >= 31) {
            val radius = if (style.backgroundEnabled) {
                (dp(32) * style.backgroundBlurPercent.coerceIn(0, 100) / 100f)
                    .roundToInt().coerceAtMost(150)
            } else 0
            window.setBackgroundBlurRadius(radius)
        }
    }

    private fun saveDraggedPosition(p: WindowManager.LayoutParams) {
        val bounds = geometry ?: return
        val previous = position ?: return
        saveRelativePosition(
            bounds.position(p.x, p.y, previous),
            usesLegacyBasis = positionUsesLegacyBasis && !bounds.hasVerticalTravel,
        )
    }

    private fun saveRelativePosition(relative: FloatingPosition, usesLegacyBasis: Boolean = false) {
        FloatingLyricsStyleStore.saveRelativePosition(context, relative, usesLegacyBasis)
        position = relative
        positionUsesLegacyBasis = usesLegacyBasis
    }

    private fun updateLayout(remap: Boolean, refreshArea: Boolean, remeasure: Boolean) {
        val view = lyricsView ?: return
        val p = params ?: return
        runCatching {
            if (remap) applyStyle(view)
            if (calculateLayout(view, p, remap, refreshArea, remeasure || remap)) {
                windowManager.updateViewLayout(view.rootView, p)
            }
        }.onFailure {
            Log.w("FloatingLyricsWindow", "Unable to update overlay geometry", it)
            hideAfterFailure()
        }
    }

    @Suppress("DEPRECATION")
    private fun availableWindowArea(view: View): FloatingWindowArea {
        windowAreaProvider?.let { return it() }
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windowManager.currentWindowMetrics
            return FloatingWindowArea.fromInsets(
                metrics.bounds.width(), metrics.bounds.height(), metrics.windowInsets
            )
        }
        // Before WindowMetrics, the attached view exposes the display frame fitted by WM.
        // Subtracting rootWindowInsets again would double-count already fitted system bars.
        if (view.isAttachedToWindow) {
            val frame = Rect()
            view.getWindowVisibleDisplayFrame(frame)
            if (!frame.isEmpty) return FloatingWindowArea(frame.width(), frame.height())
        }
        val size = Point()
        windowManager.defaultDisplay.getSize(size)
        return FloatingWindowArea(size.x.coerceAtLeast(1), size.y.coerceAtLeast(1))
    }

    private fun measureGeometry(view: TextView, area: FloatingWindowArea): FloatingWindowGeometry {
        val width = FloatingWindowGeometry.width(
            area.width, FloatingLyricsStyleStore.getStyle(context).maxWidthPercent
        )
        if (view.minWidth != width) view.minWidth = width
        if (view.maxWidth != width) view.maxWidth = width
        if (view.maxHeight != area.height) view.maxHeight = area.height
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(area.height, View.MeasureSpec.AT_MOST)
        )
        return FloatingWindowGeometry(area, width, view.measuredHeight)
    }

    private fun initialPosition(view: TextView, geometry: FloatingWindowGeometry): FloatingPosition {
        val area = geometry.area
        val (x, y) = FloatingLyricsStyleStore.getPosition(context)
        val initialY = y.coerceIn(0, area.height) + area.legacyTopOffset
        val initial = if (FloatingLyricsStyleStore.hasLegacyPosition(context)) {
            geometry.migrate(x, initialY, touchSlop)
        } else {
            geometry.position(0, initialY, FloatingPosition(0.5f, 0f)).copy(horizontalTravelFraction = 0.5f)
        }
        // Pre-30 must wait for the fitted frame before migrating pixels permanently.
        if (Build.VERSION.SDK_INT >= 30 || view.isAttachedToWindow) saveRelativePosition(initial)
        return initial
    }

    private fun calculateLayout(
        view: TextView,
        p: WindowManager.LayoutParams,
        remap: Boolean,
        refreshArea: Boolean = false,
        remeasure: Boolean = true,
    ): Boolean {
        val previous = geometry
        val area = if (refreshArea || previous == null) availableWindowArea(view) else previous.area
        val areaChanged = previous?.area != area
        val next = if (remeasure || areaChanged) measureGeometry(view, area) else previous
        if (areaChanged) gestureActive = false

        val saved = position
        val migrated = if (positionUsesLegacyBasis && saved != null) next.rebaseLegacyPosition(saved) else null
        if (migrated != null && view.isAttachedToWindow) saveRelativePosition(migrated)
        val relative = migrated ?: saved ?: initialPosition(view, next)
        val (x, y) = if (remap || areaChanged || previous.width != next.width || migrated != null) {
            next.coordinates(relative)
        } else next.clampX(p.x) to next.clampY(p.y)
        val fitChanged = if (Build.VERSION.SDK_INT >= 30 && p.fitInsetsTypes != area.fitInsetsTypes) {
            p.fitInsetsTypes = area.fitInsetsTypes
            true
        } else false
        val changed = fitChanged || p.width != next.width || p.x != x || p.y != y
        p.width = next.width
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
            windowManager.updateViewLayout(view.rootView, p)
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
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (Build.VERSION.SDK_INT >= 31) {
            flags = flags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        }
        return if (FloatingLyricsStyleStore.isClickThrough(context)) {
            flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            flags
        }
    }

    private fun dp(value: Int): Int {
        return (value * windowContext.resources.displayMetrics.density).toInt()
    }

    private companion object {
        const val DRAG_INSETS_REFRESH_DELAY_MS = 80L
    }
}
