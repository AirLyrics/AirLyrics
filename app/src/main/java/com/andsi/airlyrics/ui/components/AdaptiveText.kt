package com.andsi.airlyrics.ui.components

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.animation.doOnEnd
import androidx.core.graphics.withClip
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorTextMuted
import kotlin.math.roundToInt

internal enum class TextDisplayPolicy { Full, CompactTitle, SingleLineSummary, ExpandableSummary }

internal fun TextView.applyTextPolicy(policy: TextDisplayPolicy) {
    maxLines = when (policy) {
        TextDisplayPolicy.Full -> Int.MAX_VALUE
        TextDisplayPolicy.CompactTitle, TextDisplayPolicy.ExpandableSummary -> 2
        TextDisplayPolicy.SingleLineSummary -> 1
    }
    ellipsize = if (policy == TextDisplayPolicy.Full) null else TextUtils.TruncateAt.END
}

internal fun TextView.isTextTruncated(): Boolean =
    layout?.let { textLayout -> (0 until textLayout.lineCount).any { textLayout.getEllipsisCount(it) > 0 } } == true

internal fun MainUiHost.bindReadOnlyText(view: TextView, title: String) {
    view.applyTextPolicy(TextDisplayPolicy.SingleLineSummary)
    bindFullTextReadAction(view, { title }, { view.text.toString() }, allowScroll = true, source = view)
}

internal fun MainUiHost.bindFullTextReadAction(
    view: View,
    title: () -> String?,
    text: () -> String,
    allowScroll: Boolean = false,
    source: TextView? = null
) {
    fun open(): Boolean {
        showFullText(title(), text(), allowScroll, source)
        return true
    }
    view.setOnLongClickListener { open() }
    ViewCompat.replaceAccessibilityAction(
        view, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
        getString(R.string.ui_expand_text)
    ) { _, _ -> open() }
}

internal fun TextView.fullTextLineCount(width: Int = measuredWidth): Int {
    val available = (width - compoundPaddingLeft - compoundPaddingRight).coerceAtLeast(1)
    val displayed = transformationMethod?.getTransformation(text, this) ?: text
    return StaticLayout.Builder.obtain(displayed, 0, displayed.length, paint, available)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setTextDirection(if (layoutDirection == View.LAYOUT_DIRECTION_RTL) TextDirectionHeuristics.FIRSTSTRONG_RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
        .setIncludePad(includeFontPadding)
        .setBreakStrategy(breakStrategy)
        .setHyphenationFrequency(hyphenationFrequency)
        .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
        .build().lineCount
}

internal fun MainUiHost.expandableText(textView: TextView): ExpandableTextLayout =
    ExpandableTextLayout(this, textView)

@SuppressLint("ViewConstructor")
internal class ExpandableTextLayout(
    private val host: MainUiHost,
    val textView: TextView
) : ViewGroup(host) {
    private var expanded = false
    private var boundText: CharSequence = textView.text.toString()
    private var expansionState: MutableSet<String>? = null
    private var stateKey: String? = null
    private var canExpand = false
    private var measuredAvailableWidth = 0
    private var transitionWidth = 0
    private var animatedHeight: Int? = null
    private var animator: ValueAnimator? = null
    private val action = host.airIconView(R.drawable.ic_air_chevron_right, host.colorTextMuted).apply {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnClickListener { toggle() }
    }

    init {
        addView(textView)
        addView(action)
        updateActionLabel()
        finishExpansion()
    }

    fun bindExpansion(state: MutableSet<String>, key: String) {
        val next = key in state
        expansionState = state
        if (stateKey == key && expanded == next && TextUtils.equals(boundText, textView.text)) return
        stateKey = key
        boundText = textView.text.toString()
        expanded = next
        updateActionLabel()
        finishExpansion()
        requestLayout()
    }

    private fun toggle() {
        if (!canExpand) return
        val startHeight = animatedHeight ?: height
        val startRotation = action.rotation
        stopAnimation()
        expanded = !expanded
        stateKey?.let { key -> if (expanded) expansionState?.add(key) else expansionState?.remove(key) }
        updateActionLabel()

        if (!isLaidOut || !action.canAnimateFloatingControl()) {
            finishExpansion()
            requestLayout()
            return
        }

        transitionWidth = measuredAvailableWidth
        setTextExpanded(expanded)
        measureText(transitionWidth - action.measuredWidth)
        val targetHeight = naturalHeight()
        val targetRotation = targetRotation()

        setTextExpanded(true)
        animatedHeight = startHeight
        requestLayout()
        val motion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = AirUiTokens.Motion.LayoutChangeMs
            interpolator = floatingPanelEaseOut
            addUpdateListener {
                val amount = it.animatedValue as Float
                val nextHeight = (startHeight + (targetHeight - startHeight) * amount).roundToInt()
                if (nextHeight != animatedHeight) {
                    animatedHeight = nextHeight
                    requestLayout()
                }
                action.rotation = startRotation + (targetRotation - startRotation) * amount
            }
            doOnEnd {
                if (animator === it) {
                    animator = null
                    finishExpansion()
                    requestLayout()
                }
            }
        }
        animator = motion
        motion.start()
    }

    private fun updateActionLabel() {
        action.contentDescription = host.getString(if (expanded) R.string.ui_collapse_text else R.string.ui_expand_text)
        action.tooltipText = action.contentDescription
    }

    private fun targetRotation() = if (expanded) -90f else 90f

    private fun setTextExpanded(expanded: Boolean) {
        textView.maxLines = if (expanded) Int.MAX_VALUE else 2
        textView.ellipsize = if (expanded) null else TextUtils.TruncateAt.END
    }

    private fun stopAnimation() {
        val running = animator
        animator = null
        running?.cancel()
    }

    private fun finishExpansion() {
        stopAnimation()
        animatedHeight = null
        action.rotation = targetRotation()
        setTextExpanded(expanded)
    }

    override fun onDetachedFromWindow() {
        finishExpansion()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!TextUtils.equals(boundText, textView.text)) {
            boundText = textView.text.toString()
            expanded = false
            updateActionLabel()
            finishExpansion()
        }
        val bounded = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
        val available = if (bounded) {
            (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        } else 0
        // An unconstrained measurement may be a parent's probe before the actual layout.
        // Only a changed bounded width invalidates the animation's measured heights.
        if (bounded && animatedHeight != null && available != transitionWidth) finishExpansion()
        val moving = bounded && animatedHeight != null
        measuredAvailableWidth = available
        if (moving) {
            canExpand = true
        } else if (bounded) {
            measureText(available)
            canExpand = textView.fullTextLineCount(available) > 2
        } else {
            textView.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
            canExpand = false
        }

        action.visibility = if (canExpand) VISIBLE else GONE
        val actionWidth = if (canExpand) host.dp(AirUiTokens.Layout.IconTouchSize).coerceAtMost(available) else 0
        val actionSpec = MeasureSpec.makeMeasureSpec(actionWidth, MeasureSpec.EXACTLY)
        action.measure(actionSpec, actionSpec)
        if (canExpand) measureText(available - actionWidth)
        setMeasuredDimension(
            resolveSize(textView.measuredWidth + actionWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(if (moving) requireNotNull(animatedHeight) else naturalHeight(), heightMeasureSpec),
        )
    }

    private fun measureText(available: Int) {
        textView.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
    }

    private fun naturalHeight() = maxOf(textView.measuredHeight, action.measuredHeight) + paddingTop + paddingBottom

    override fun dispatchDraw(canvas: Canvas) {
        if (animatedHeight == null) {
            super.dispatchDraw(canvas)
            return
        }
        canvas.withClip(0, 0, width, height) {
            super.dispatchDraw(this)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val textX = if (rtl) width - paddingRight - textView.measuredWidth else paddingLeft
        textView.layout(textX, paddingTop, textX + textView.measuredWidth, paddingTop + textView.measuredHeight)
        if (canExpand) {
            val x = if (rtl) paddingLeft else width - paddingRight - action.measuredWidth
            action.layout(x, paddingTop, x + action.measuredWidth, paddingTop + action.measuredHeight)
        }
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
}
