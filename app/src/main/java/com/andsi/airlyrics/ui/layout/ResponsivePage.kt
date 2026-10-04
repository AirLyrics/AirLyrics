package com.andsi.airlyrics.ui.layout

import android.annotation.SuppressLint
import com.andsi.airlyrics.R
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.state.bindInteractionScroll
import com.andsi.airlyrics.ui.state.markInteractionAnchors

/** Reparents the same cards at breakpoints; it never recreates their controls or callbacks. */
@SuppressLint("ViewConstructor")
internal class ResponsivePage(
    private val host: MainUiHost,
    source: LinearLayout,
    private val leftIndices: Set<Int>,
    private val key: String
) : ViewGroup(host) {
    private val cards = (0 until source.childCount).map(source::getChildAt)
    private val cardParams = cards.map { LinearLayout.LayoutParams(it.layoutParams as? MarginLayoutParams ?: MarginLayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }
    private val padding = source.paddingLeft.coerceAtLeast(host.dp(16))
    private var mode = 0
    private var lastWidth = -1
    private var lastHeight = -1
    private var lastFontScale = -1f
    internal val columnCount get() = mode
    private var bindings = emptyList<() -> Unit>()
    private var panes = emptyList<ScrollView>()

    init {
        // Structural moves must not leave disappearing children attached to their old parent.
        source.layoutTransition = null
        cards.forEachIndexed { index, view -> view.markInteractionAnchors("$key.card.$index") }
        addView(source)
    }

    private fun configure(columns: Int) {
        host.interactionUi.capture()
        bindings.forEach { it() }
        cards.forEach { card ->
            (card.parent as? ViewGroup)?.let { parent ->
                parent.layoutTransition = null
                parent.removeView(card)
                parent.endViewTransition(card)
            }
        }
        removeAllViews()
        val previous = if (mode == 0) host.interactions.read("$key.layout")?.getInt("columns") ?: 0 else mode
        mode = columns
        host.interactions.write("$key.layout") { putInt("columns", columns) }
        if (previous != 0 && previous != columns) {
            val from = if (previous == 1) "$key.single" else host.interactions.read("$key.active")?.getString("key") ?: "$key.left"
            host.interactions.read(from)?.let { snapshot ->
                val anchor = snapshot.getString("anchor")
                val index = cards.indexOfFirst { it.containsAnchor(anchor) }
                val target = if (columns == 1) "$key.single" else when {
                    index < 0 -> host.interactions.read("$key.active")?.getString("key")?.takeIf { it != "$key.single" } ?: "$key.left"
                    index in leftIndices -> "$key.left"
                    else -> "$key.right"
                }
                host.interactions.write(target) { clear(); putAll(snapshot) }
            }
        }
        panes = (0 until columns).map { column ->
            val body = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, host.dp(if (host.windowLayout.compact) 4 else 12), padding, host.dp(24))
            }
            cards.forEachIndexed { index, card ->
                if (columns == 1 || (index in leftIndices) == (column == 0)) {
                    body.addView(card, cardParams[index])
                }
            }
            ScrollView(host).apply { addView(body); addViewToPage(this) }
        }
        bindPanes()
    }

    private fun bindPanes() {
        bindings = panes.mapIndexed { index, pane ->
            val paneKey = if (mode == 1) "$key.single" else "$key.${if (index == 0) "left" else "right"}"
            host.bindInteractionScroll(pane, paneKey) {
                host.interactions.write("$key.active") { putString("key", paneKey) }
            }
        }
    }

    private fun addViewToPage(view: View) { addView(view) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val density = resources.displayMetrics.density
        val boundedWidth = width.coerceAtMost(host.dp(WindowLayoutSpec.DOUBLE_MAX))
        val columns = host.windowLayout.columns((boundedWidth - padding * 4) / density)
        if (mode != columns) {
            configure(columns)
        } else if (lastWidth != width || lastHeight != height || lastFontScale != host.windowLayout.fontScale) {
            host.interactionUi.capture()
            bindings.forEach { it() }
            bindPanes()
        }
        lastWidth = width
        lastHeight = height
        lastFontScale = host.windowLayout.fontScale
        val maxWidth = host.dp(if (columns == 2) WindowLayoutSpec.DOUBLE_MAX else WindowLayoutSpec.SINGLE_MAX)
        val contentWidth = width.coerceAtMost(maxWidth)
        val gap = if (columns == 2) host.dp(WindowLayoutSpec.GAP) else 0
        panes.forEach { pane ->
            pane.getChildAt(0).setPadding(padding, host.dp(if (host.windowLayout.compact) 4 else 12), padding, host.dp(24))
            pane.measure(MeasureSpec.makeMeasureSpec((contentWidth - gap) / columns, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val gap = if (mode == 2) host.dp(WindowLayoutSpec.GAP) else 0
        val total = panes.sumOf { it.measuredWidth } + gap
        var x = (width - total) / 2
        val ordered = if (layoutDirection == LAYOUT_DIRECTION_RTL) panes.reversed() else panes
        ordered.forEach { it.layout(x, 0, x + it.measuredWidth, height); x += it.measuredWidth + gap }
    }

    override fun onDetachedFromWindow() {
        bindings.forEach { it() }
        super.onDetachedFromWindow()
    }
}

private fun View.containsAnchor(anchor: String?): Boolean = anchor != null &&
    (getTag(R.id.interaction_anchor) == anchor || (this is ViewGroup && (0 until childCount).any { getChildAt(it).containsAnchor(anchor) }))
