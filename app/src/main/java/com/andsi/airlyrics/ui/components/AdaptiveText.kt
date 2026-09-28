package com.andsi.airlyrics.ui.components

import android.annotation.SuppressLint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorTextMuted

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

/** Only for read-only values; never consumes a setting's primary click. */
internal fun MainUiHost.bindReadOnlyText(view: TextView, title: String) {
    view.applyTextPolicy(TextDisplayPolicy.SingleLineSummary)
    bindFullTextReadAction(view, { title }, { view.text.toString() }, allowScroll = true, source = view)
}

/** Shared long-press and screen-reader action; values are read when opened, not when bound. */
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

/** Uses the same metrics as TextView, including compound icons, spans and font scaling. */
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

/** The expansion control owns its click, leaving the enclosing card's action untouched. */
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
    private val action = host.airIconView(R.drawable.ic_air_chevron_right, host.colorTextMuted).apply {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnClickListener {
            expanded = !expanded
            stateKey?.let { key -> if (expanded) expansionState?.add(key) else expansionState?.remove(key) }
            updateAction()
            requestLayout()
        }
    }

    init {
        addView(textView)
        addView(action)
        updateAction()
    }

    fun bindExpansion(state: MutableSet<String>, key: String) {
        expansionState = state
        stateKey = key
        boundText = textView.text.toString()
        expanded = key in state
        updateAction()
        requestLayout()
    }

    private fun updateAction() {
        action.rotation = if (expanded) -90f else 90f
        action.contentDescription = host.getString(if (expanded) R.string.ui_collapse_text else R.string.ui_expand_text)
        action.tooltipText = action.contentDescription
        textView.maxLines = if (expanded) Int.MAX_VALUE else 2
        textView.ellipsize = if (expanded) null else TextUtils.TruncateAt.END
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!TextUtils.equals(boundText, textView.text)) {
            boundText = textView.text.toString()
            expanded = false
            updateAction()
        }
        val bounded = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
        val available = if (bounded) (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0) else 0
        textView.measure(if (bounded) MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST) else MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        canExpand = bounded && textView.fullTextLineCount(available) > 2
        action.visibility = if (canExpand) VISIBLE else GONE
        val actionWidth = if (canExpand) host.dp(AirUiTokens.Layout.IconTouchSize).coerceAtMost(available) else 0
        action.measure(MeasureSpec.makeMeasureSpec(actionWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(actionWidth, MeasureSpec.EXACTLY))
        if (canExpand) textView.measure(MeasureSpec.makeMeasureSpec((available - actionWidth).coerceAtLeast(0), MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
        setMeasuredDimension(
            resolveSize(textView.measuredWidth + actionWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(maxOf(textView.measuredHeight, action.measuredHeight) + paddingTop + paddingBottom, heightMeasureSpec)
        )
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
