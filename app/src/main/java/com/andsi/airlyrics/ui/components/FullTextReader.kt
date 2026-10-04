package com.andsi.airlyrics.ui.components

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.ui.state.rememberReader
import com.andsi.airlyrics.ui.state.isAuxiliaryDialogShowing
import com.andsi.airlyrics.ui.state.forgetAuxiliaryDialog
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorTextStrong
import java.lang.ref.WeakReference

/** Full static text is always the default; motion is an optional, explicit reading aid. */
internal fun MainUiHost.showFullText(
    title: String?,
    text: String,
    allowScroll: Boolean = false,
    source: TextView? = null
) {
    if (isAuxiliaryDialogShowing("reader")) return
    val host = this
    rememberReader("reader", title, text, allowScroll)
    auxiliaryDialogs["reader"] = showAirDialog(title = title, scrollStateKey = "aux.reader.scroll",
        onUserDismiss = { forgetAuxiliaryDialog("reader") }, body = {
        addView(FullTextReader(host, text, source, allowScroll))
    })
}

@SuppressLint("ViewConstructor")
internal class FullTextReader(
    private val host: MainUiHost,
    text: String,
    private val source: TextView? = null,
    private val allowScroll: Boolean = false
) : LinearLayout(host) {
    private val accessibility = host.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private val staticText = TextView(host).apply {
        this.text = text
        textSize = AirUiTokens.TextSize.Body
        setTextColor(host.colorTextStrong)
        setTextIsSelectable(true)
    }
    private val movingText = TextView(host).apply {
        this.text = text
        textSize = AirUiTokens.TextSize.Body
        setTextColor(host.colorTextStrong)
        setSingleLine(true)
    }
    private val viewport = HorizontalScrollView(host).apply {
        visibility = GONE
        isHorizontalScrollBarEnabled = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(movingText)
    }
    private val scrollAction = actionButton(host, host.getString(R.string.ui_scroll_text)) { startReading() }.apply {
        minimumHeight = host.dp(AirUiTokens.Layout.IconTouchSize)
    }
    private val readControl = FrameLayout(host).apply {
        addView(scrollAction, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(viewport, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER_VERTICAL))
        visibility = if (allowScroll && motionAllowed()) VISIBLE else GONE
    }
    private var animator: ValueAnimator? = null
    internal var reading = false
        private set
    private val finish = Runnable { stopReading() }
    private val begin = Runnable { animateText() }
    private val accessibilityListener = AccessibilityManager.TouchExplorationStateChangeListener {
        if (it) stopReading()
    }
    private val sourceWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            stopReading()
            staticText.text = s
            movingText.text = s
        }
        override fun afterTextChanged(s: Editable?) = Unit
    }
    private val sourceAttachment = object : OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) { stopReading() }
    }

    init {
        orientation = VERTICAL
        addView(staticText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(readControl, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    internal fun motionAllowed() = ValueAnimator.areAnimatorsEnabled() && !accessibility.isTouchExplorationEnabled

    internal fun startReading() {
        stopReading()
        if (!allowScroll || !motionAllowed() || !isAttachedToWindow || !hasWindowFocus()) return
        activeReader?.get()?.stopReading()
        activeReader = WeakReference(this)
        reading = true
        // Keep the full text visible. Motion replaces only the fixed-size action area.
        scrollAction.visibility = INVISIBLE
        viewport.visibility = VISIBLE
        postDelayed(begin, AirUiTokens.Motion.TextScrollDelayMs)
    }

    private fun animateText() {
        if (!reading || !motionAllowed() || !hasWindowFocus()) {
            stopReading()
            return
        }
        val distance = (movingText.width - viewport.width).coerceAtLeast(0)
        if (distance == 0) {
            stopReading()
            return
        }
        val rtl = movingText.layout?.getParagraphDirection(0) == -1
        animator = ValueAnimator.ofInt(if (rtl) distance else 0, if (rtl) 0 else distance).apply {
            duration = (distance * 1000L / host.dp(AirUiTokens.Motion.TextScrollDpPerSecond).coerceAtLeast(1)).coerceAtLeast(1)
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (!motionAllowed()) stopReading() else viewport.scrollTo(it.animatedValue as Int, 0)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (reading) postDelayed(finish, AirUiTokens.Motion.TextScrollEndHoldMs)
                }
            })
            start()
        }
    }

    internal fun stopReading() {
        reading = false
        removeCallbacks(begin)
        removeCallbacks(finish)
        animator?.cancel()
        animator = null
        viewport.scrollTo(0, 0)
        viewport.visibility = GONE
        scrollAction.visibility = VISIBLE
        if (activeReader?.get() === this) activeReader = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        source?.addTextChangedListener(sourceWatcher)
        source?.addOnAttachStateChangeListener(sourceAttachment)
        accessibility.addTouchExplorationStateChangeListener(accessibilityListener)
    }

    override fun onDetachedFromWindow() {
        stopReading()
        source?.removeTextChangedListener(sourceWatcher)
        source?.removeOnAttachStateChangeListener(sourceAttachment)
        accessibility.removeTouchExplorationStateChangeListener(accessibilityListener)
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) stopReading()
    }

    companion object {
        // At most one moving reader, without retaining a window or Activity.
        private var activeReader: WeakReference<FullTextReader>? = null
    }
}
