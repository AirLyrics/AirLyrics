package com.andsi.airlyrics.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityManager
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.MainUiHost
import java.lang.ref.WeakReference

/** One user-started pass, reading overflowing lines in order without moving any layout. */
internal class InlineTextScrollController(
    private val anchor: View,
    private val lines: List<InlineOverflowTextView>
) {
    private val accessibility = anchor.context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private var currentLine: InlineOverflowTextView? = null
    private var distance = 0
    private var startTime = 0L
    private var duration = 0L
    private val frame = Runnable { drawFrame() }
    private var observer: ViewTreeObserver? = null
    private var nextIndex = 0
    private var running = false
    private var scrollPositions: List<Triple<View, Int, Int>> = emptyList()
    private val nextLine = Runnable {
        lines.forEach { it.endReading() }
        readNextLine()
    }
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { if (!it) stop() }
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener {
        // TextView internals and press transforms can also dispatch this event.
        // Only actual scrolling of the card or its ancestors should interrupt reading.
        if (scrollPositions.any { (view, x, y) -> view.scrollX != x || view.scrollY != y }) stop()
    }
    private val visibilityListener = ViewTreeObserver.OnPreDrawListener {
        if (!anchor.isShown) stop()
        true
    }
    private val accessibilityListener = AccessibilityManager.TouchExplorationStateChangeListener {
        if (it) stop()
    }

    init {
        lines.forEach { it.onReadingInvalidated = ::stop }
        anchor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { stop() }
        })
    }

    internal fun bind(host: MainUiHost, staticFallback: () -> Unit) {
        fun read(): Boolean {
            if (motionAllowed()) start() else {
                stop()
                staticFallback()
            }
            return true
        }
        anchor.setOnLongClickListener { read() }
        ViewCompat.replaceAccessibilityAction(
            anchor, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
            host.getString(R.string.ui_scroll_text)
        ) { _, _ -> read() }
    }

    private fun motionAllowed() = ValueAnimator.areAnimatorsEnabled() && !accessibility.isTouchExplorationEnabled

    private fun start() {
        stop()
        if (!anchor.isAttachedToWindow || !anchor.isShown || !anchor.hasWindowFocus()) return
        if (lines.none { it.overflowLayout() != null }) return
        active?.get()?.stop()
        active = WeakReference(this)
        running = true
        nextIndex = 0
        scrollPositions = generateSequence(anchor) { it.parent as? View }
            .map { Triple(it, it.scrollX, it.scrollY) }.toList()
        observer = anchor.viewTreeObserver.also {
            it.addOnWindowFocusChangeListener(focusListener)
            it.addOnScrollChangedListener(scrollListener)
            it.addOnPreDrawListener(visibilityListener)
        }
        accessibility.addTouchExplorationStateChangeListener(accessibilityListener)
        readNextLine()
    }

    private fun readNextLine() {
        if (!running) return
        while (nextIndex < lines.size) {
            val line = lines[nextIndex++]
            val fullLayout = line.overflowLayout() ?: continue
            currentLine = line
            distance = line.beginReading(fullLayout)
            val pixelsPerSecond = AirUiTokens.Motion.TextScrollDpPerSecond * anchor.resources.displayMetrics.density
            duration = (distance * 1000 / pixelsPerSecond).toLong().coerceAtLeast(1)
            startTime = SystemClock.uptimeMillis() + AirUiTokens.Motion.TextScrollDelayMs
            anchor.postOnAnimationDelayed(frame, AirUiTokens.Motion.TextScrollDelayMs)
            return
        }
        stop()
    }

    private fun drawFrame() {
        if (!running) return
        if (!motionAllowed()) {
            stop()
            return
        }
        // Reading speed is independent of decorative press animations and system duration scales.
        val progress = ((SystemClock.uptimeMillis() - startTime).toFloat() / duration).coerceIn(0f, 1f)
        currentLine?.setReadingProgress(progress, distance)
        if (progress < 1f) anchor.postOnAnimation(frame)
        else anchor.postDelayed(nextLine, AirUiTokens.Motion.TextScrollEndHoldMs)
    }

    internal fun stop() {
        running = false
        anchor.removeCallbacks(nextLine)
        anchor.removeCallbacks(frame)
        currentLine = null
        lines.forEach { it.endReading() }
        observer?.takeIf { it.isAlive }?.apply {
            removeOnWindowFocusChangeListener(focusListener)
            removeOnScrollChangedListener(scrollListener)
            removeOnPreDrawListener(visibilityListener)
        }
        observer = null
        scrollPositions = emptyList()
        accessibility.removeTouchExplorationStateChangeListener(accessibilityListener)
        if (active?.get() === this) active = null
    }

    companion object {
        private var active: WeakReference<InlineTextScrollController>? = null
    }
}
