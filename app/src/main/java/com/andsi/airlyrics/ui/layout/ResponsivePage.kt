package com.andsi.airlyrics.ui.layout

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.color.AirColorUtils
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.state.bindInteractionScroll
import com.andsi.airlyrics.ui.state.markInteractionAnchors
import com.andsi.airlyrics.ui.theme.bindAppearance
import com.andsi.airlyrics.ui.theme.colorBackground
import kotlin.math.roundToInt

/** Reparents the same cards at breakpoints; it never recreates their controls or callbacks. */
@SuppressLint("ViewConstructor")
internal class ResponsivePage(
    private val host: MainUiHost,
    source: LinearLayout,
    private val leftIndices: Set<Int>,
    private val key: String,
    private val singleColumnHeader: Header? = null
) : ViewGroup(host) {
    internal data class Header(
        val cardIndex: Int,
        val isExpanded: () -> Boolean,
        val expansionFraction: () -> Float = { if (isExpanded()) 1f else 0f }
    )

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
    private var header: PinnedPageHeader? = null

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
        val headerSpec = singleColumnHeader.takeIf { columns == 1 }
        header = headerSpec?.let { spec ->
            PinnedPageHeader(host, padding, spec).apply {
                addView(cards[spec.cardIndex], cardParams[spec.cardIndex])
            }
        }
        panes = (0 until columns).map { column ->
            val body = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            cards.forEachIndexed { index, card ->
                if (index != headerSpec?.cardIndex && (columns == 1 || (index in leftIndices) == (column == 0))) {
                    body.addView(card, cardParams[index])
                }
            }
            val pane = if (headerSpec == null) ScrollView(host) else HeaderScrollView(host)
            pane.addView(body)
            addView(pane)
            pane
        }
        header?.let { addView(it) }
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
        val paneWidthSpec = MeasureSpec.makeMeasureSpec((contentWidth - gap) / columns, MeasureSpec.EXACTLY)
        val topPadding = if (header == null) host.dp(if (host.windowLayout.compact) 4 else 12) else 0
        header?.measure(paneWidthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
        val scrollInset = header?.scrollInset ?: 0
        val paneHeightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        panes.forEach { pane ->
            pane.setPadding(0, scrollInset, 0, 0)
            pane.getChildAt(0).setPadding(padding, topPadding, padding, host.dp(24))
            pane.measure(paneWidthSpec, paneHeightSpec)
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val gap = if (mode == 2) host.dp(WindowLayoutSpec.GAP) else 0
        val total = panes.sumOf { it.measuredWidth } + gap
        var x = (width - total) / 2
        header?.let { view -> view.layout(x, 0, x + view.measuredWidth, view.measuredHeight) }
        val ordered = if (layoutDirection == LAYOUT_DIRECTION_RTL) panes.reversed() else panes
        ordered.forEach { pane ->
            pane.layout(x, 0, x + pane.measuredWidth, height)
            x += pane.measuredWidth + gap
        }
        renderHeaderExpansion()
    }

    internal fun renderHeaderExpansion() {
        val pinnedHeader = header ?: return
        pinnedHeader.invalidate()
        (panes.singleOrNull() as? HeaderScrollView)?.renderExpansion(pinnedHeader.expansionFraction)
    }

    override fun onDetachedFromWindow() {
        bindings.forEach { it() }
        super.onDetachedFromWindow()
    }
}

@SuppressLint("ViewConstructor")
private class PinnedPageHeader(
    private val host: MainUiHost,
    private val horizontalPadding: Int,
    private val header: ResponsivePage.Header
) : LinearLayout(host) {
    private val fadeHeight = host.dp(14)
    private val scrim = ColorDrawable()
    private val fade = GradientDrawable().apply { orientation = GradientDrawable.Orientation.TOP_BOTTOM }
    val expansionFraction get() = header.expansionFraction()
    var scrollInset = 0
        private set

    init {
        orientation = VERTICAL
        host.bindAppearance(this) {
            val color = AirColorUtils.withAlpha(host.colorBackground, 204)
            scrim.color = color
            fade.colors = intArrayOf(color, AirColorUtils.withAlpha(color, 0))
            invalidate()
        }
    }

    private fun hasExpandedBounds() = header.isExpanded() || expansionFraction > 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val top = host.dp(AirUiTokens.Space.PageTop)
        val bottom = if (hasExpandedBounds()) fadeHeight else 0
        setPadding(horizontalPadding, top, horizontalPadding, bottom)

        val available = MeasureSpec.getSize(heightMeasureSpec)
        val toggleHeight = getChildAt(0).minimumHeight
        val limit = maxOf(available / 2, top + toggleHeight + bottom).coerceAtMost(available)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
        scrollInset = if (hasExpandedBounds()) {
            (measuredHeight - bottom + host.dp(AirUiTokens.Space.Sm)).coerceIn(0, available)
        } else {
            0
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val fraction = expansionFraction
        if (fraction > 0f) {
            val bottom = (fadeHeight + (height - fadeHeight) * fraction).roundToInt().coerceIn(0, height)
            val fadeTop = (bottom - fadeHeight).coerceAtLeast(0)
            val alpha = (255 * fraction).roundToInt()
            scrim.alpha = alpha
            scrim.setBounds(0, 0, width, fadeTop)
            scrim.draw(canvas)
            fade.alpha = alpha
            fade.setBounds(0, fadeTop, width, bottom)
            fade.draw(canvas)
        }
        super.dispatchDraw(canvas)
    }
}

private class HeaderScrollView(context: Context) : ScrollView(context) {
    private var expansionFraction = 1f

    init {
        clipToPadding = false
    }

    fun renderExpansion(fraction: Float) {
        expansionFraction = fraction
        alignContentToHeader()
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        alignContentToHeader()
    }

    private fun alignContentToHeader() {
        val content = getChildAt(0) ?: return
        // The expanded viewport stays measured during the animation. Account for the
        // scroll offset the framework will clamp when the header frees its space.
        val collapsedRange = (content.height - height).coerceAtLeast(0)
        val bottomClamp = (scrollY - collapsedRange).coerceIn(0, paddingTop)
        content.translationY = (bottomClamp - paddingTop) * (1f - expansionFraction)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        super.onInterceptTouchEvent(event) ||
            (event.actionMasked == MotionEvent.ACTION_DOWN && event.y < paddingTop * expansionFraction)

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        val visibleTop = scrollY + paddingTop
        if (paddingTop > 0 && rect.top < visibleTop && rect.bottom <= scrollY + height) {
            return (rect.top - visibleTop).coerceAtLeast(-scrollY)
        }
        return super.computeScrollDeltaToGetChildRectOnScreen(rect)
    }
}

private fun View.containsAnchor(anchor: String?): Boolean = anchor != null &&
    (getTag(R.id.interaction_anchor) == anchor || (this is ViewGroup && (0 until childCount).any { getChildAt(it).containsAnchor(anchor) }))
