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
import androidx.core.view.children
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.color.AirColorUtils
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.state.bindInteractionScroll
import com.andsi.airlyrics.ui.state.markInteractionAnchors
import com.andsi.airlyrics.ui.theme.bindAppearance
import com.andsi.airlyrics.ui.theme.colorBackground
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
internal class ResponsivePage(
    private val host: MainUiHost,
    private val source: LinearLayout,
    private val key: String,
    pinnedHeader: Header? = null,
) : ViewGroup(host) {
    internal data class Header(
        val view: View,
        val isExpanded: () -> Boolean,
        val expansionFraction: () -> Float = { if (isExpanded()) 1f else 0f }
    )

    private val header = pinnedHeader?.let { PinnedPageHeader(host, it) }
    private val scroll = if (header == null) ScrollView(host) else HeaderScrollView(host)
    private var releaseScroll: (() -> Unit)? = null
    private var lastLayout: WindowLayoutSpec? = null
    private var preferredAnchor: String? = null

    init {
        source.layoutTransition = null
        source.children.forEachIndexed { index, view -> view.markInteractionAnchors("$key.card.$index") }
        scroll.addView(source)
        addView(scroll)
        header?.let(::addView)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (host.interactions.read(key) == null) {
            val oldPane = if (host.interactions.read("$key.layout")?.getInt("columns") == 2) {
                host.interactions.read("$key.active")?.getString("key") ?: "$key.left"
            } else "$key.single"
            host.interactions.read(oldPane)?.let { snapshot ->
                host.interactions.write(key) { putAll(snapshot) }
            }
        }
        preferredAnchor = host.interactions.read(key)?.getString("preferredAnchor")
        bindScroll()
    }

    internal fun rememberScrollAnchor(view: View) {
        preferredAnchor = view.getTag(R.id.interaction_anchor) as? String
    }

    private fun bindScroll() {
        releaseScroll?.invoke()
        releaseScroll = host.bindInteractionScroll(scroll, key) { preferredAnchor }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val contentWidth = width.coerceAtMost(host.dp(WindowLayoutSpec.PAGE_MAX_WIDTH_DP))
        val layout = host.windowLayout
        val viewportChanged = (contentWidth != scroll.measuredWidth) ||
            (height != scroll.measuredHeight) || (layout != lastLayout)
        if ((releaseScroll != null) && scroll.isLaidOut && viewportChanged) {
            host.interactionUi.capture()
            bindScroll()
        }
        lastLayout = layout
        val inset = host.dp(layout.pageInsetDp).coerceAtMost(contentWidth / 2)
        val pageTop = if (header == null) host.dp(AirUiTokens.Space.PageTop) else 0
        source.setPadding(inset, pageTop, inset, host.dp(AirUiTokens.Space.PageBottom))
        header?.measure(
            MeasureSpec.makeMeasureSpec(contentWidth - inset * 2, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST)
        )
        scroll.setPadding(0, header?.scrollInset ?: 0, 0, 0)
        scroll.measure(
            MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val x = (width - scroll.measuredWidth) / 2
        scroll.layout(x, 0, x + scroll.measuredWidth, height)
        header?.let {
            val start = x + source.paddingLeft
            it.layout(start, 0, start + it.measuredWidth, it.measuredHeight)
        }
        renderHeaderExpansion()
    }

    internal fun renderHeaderExpansion() {
        val pinnedHeader = header ?: return
        pinnedHeader.invalidate()
        (scroll as HeaderScrollView).renderExpansion(pinnedHeader.expansionFraction)
    }

    override fun onDetachedFromWindow() {
        releaseScroll?.invoke()
        releaseScroll = null
        super.onDetachedFromWindow()
    }
}

@SuppressLint("ViewConstructor")
private class PinnedPageHeader(
    private val host: MainUiHost,
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
        addView(header.view)
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
        setPadding(0, top, 0, bottom)

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
