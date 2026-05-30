package com.andsi.airlyrics.floating

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.andsi.airlyrics.settings.store.FloatingLyricsStyleStore
import com.andsi.airlyrics.i18n.localizeText

/**
 * Owns the floating lyrics window itself: creation, removal, dragging,
 * style application, lock state and click-through behavior.
 *
 * FloatingLyricsService keeps the media / lyrics state, while this class keeps
 * the Android WindowManager details in one small box.
 */
class FloatingWindowController(
    private val context: Context,
    private val onVisibilityChanged: (Boolean) -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var lyricsView: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private var startX = 0
    private var startY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f

    val textView: TextView?
        get() = lyricsView

    val isVisible: Boolean
        get() = lyricsView != null

    fun show(): Boolean {
        if (!Settings.canDrawOverlays(context)) return false

        lyricsView?.let {
            applyStyle()
            onVisibilityChanged(true)
            return true
        }

        val view = TextView(context).apply {
            text = context.localizeText("♪ 等待媒体信息...")
            includeFontPadding = false
        }

        val (savedX, savedY) = FloatingLyricsStyleStore.getPosition(context)
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            windowFlagsForCurrentBehavior(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        view.setOnTouchListener { _, event -> handleTouch(view, event) }

        lyricsView = view
        params = layoutParams
        applyStyle(view)
        windowManager.addView(view, layoutParams)
        onVisibilityChanged(true)
        return true
    }

    fun hide(notifyVisibilityChanged: Boolean = true) {
        val view = lyricsView
        if (view != null) {
            runCatching { windowManager.removeView(view) }
        }

        lyricsView = null
        params = null
        if (notifyVisibilityChanged) {
            onVisibilityChanged(false)
        }
    }

    fun setText(text: CharSequence) {
        lyricsView?.text = text
    }

    fun applyStyle() {
        val view = lyricsView ?: return
        applyStyle(view)
        val p = params ?: return
        windowManager.updateViewLayout(view, p)
    }

    fun setLocked(locked: Boolean) {
        FloatingLyricsStyleStore.setLocked(context, locked)
        updateWindowBehavior()
    }

    fun setClickThrough(clickThrough: Boolean) {
        FloatingLyricsStyleStore.setClickThrough(context, clickThrough)
        updateWindowBehavior()
    }

    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        val p = params ?: return false

        if (FloatingLyricsStyleStore.isLocked(context)) {
            return true
        }

        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = p.x
                startY = p.y
                touchStartX = event.rawX
                touchStartY = event.rawY
                true
            }

            MotionEvent.ACTION_MOVE -> {
                p.x = startX + (event.rawX - touchStartX).toInt()
                p.y = startY + (event.rawY - touchStartY).toInt()
                windowManager.updateViewLayout(view, p)
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                FloatingLyricsStyleStore.savePosition(context, p.x, p.y)
                true
            }

            else -> true
        }
    }

    private fun applyStyle(view: TextView) {
        val style = FloatingLyricsStyleStore.getStyle(context)
        val screenWidth = context.resources.displayMetrics.widthPixels
        val maxWidth = (screenWidth * style.maxWidthPercent / 100f).toInt()

        view.textSize = style.textSizeSp
        view.setTextColor(style.textColor)
        view.gravity = style.gravity
        view.textAlignment = View.TEXT_ALIGNMENT_GRAVITY
        view.minWidth = maxWidth
        view.maxWidth = maxWidth
        view.setPadding(
            dp(style.paddingHorizontalDp),
            dp(style.paddingVerticalDp),
            dp(style.paddingHorizontalDp),
            dp(style.paddingVerticalDp)
        )

        if (style.shadowRadius > 0f) {
            view.setShadowLayer(style.shadowRadius, 0f, 0f, style.shadowColor)
        } else {
            view.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }

        view.background = if (style.backgroundEnabled) {
            GradientDrawable().apply {
                cornerRadius = dp(style.cornerRadiusDp).toFloat()
                setColor(withAlpha(style.backgroundColor, style.backgroundAlpha))
            }
        } else {
            null
        }
    }

    private fun updateWindowBehavior() {
        val view = lyricsView ?: return
        val p = params ?: return
        p.flags = windowFlagsForCurrentBehavior()
        windowManager.updateViewLayout(view, p)
    }

    private fun windowFlagsForCurrentBehavior(): Int {
        return if (FloatingLyricsStyleStore.isClickThrough(context)) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
