package com.andsi.airlyrics.app.search

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.theme.AirLyricsPalette

/** Reserves all six dots so the centered label stays still as the dots advance. */
@SuppressLint("ViewConstructor")
internal class LyricsLoadingView(context: Context, palette: AirLyricsPalette) : AppCompatTextView(context) {
    private val label = context.getString(R.string.ui_loading).trimEnd('…', '.', ' ')
    private val dotColor = ColorUtils.blendARGB(palette.accent, palette.textStrong, 0.35f)
    private var active = false
    private var dots = 1
    private val advance = object : Runnable {
        override fun run() {
            if (!active || !isAttachedToWindow || !isShown) return
            if (!ValueAnimator.areAnimatorsEnabled()) {
                dots = 3
                renderDots()
                return
            }
            dots = dots % 6 + 1
            renderDots()
            postDelayed(this, 450)
        }
    }

    init {
        textSize = 15f
        setTextColor(ColorUtils.blendARGB(palette.textMuted, palette.textStrong, 0.3f))
        gravity = Gravity.CENTER
        val padding = (12 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        minimumHeight = (48 * resources.displayMetrics.density).toInt()
        contentDescription = context.getString(R.string.ui_loading)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        renderDots()
    }

    private fun renderDots() {
        text = SpannableString("$label......").apply {
            setSpan(ForegroundColorSpan(dotColor), label.length, label.length + dots, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (dots < 6) {
                setSpan(ForegroundColorSpan(Color.TRANSPARENT), label.length + dots, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && !active) {
            active = true
            dots = if (ValueAnimator.areAnimatorsEnabled()) 1 else 3
            renderDots()
            if (ValueAnimator.areAnimatorsEnabled()) postDelayed(advance, 450)
        } else if (!isVisible) {
            stopAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.ProgressBar"
    }

    private fun stopAnimation() {
        removeCallbacks(advance)
        active = false
    }
}
