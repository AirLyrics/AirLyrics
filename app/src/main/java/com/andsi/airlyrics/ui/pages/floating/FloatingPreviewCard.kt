package com.andsi.airlyrics.ui.pages.floating

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.animation.doOnEnd
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.color.AirColorUtils
import com.andsi.airlyrics.core.model.FloatingLyricsStyle
import com.andsi.airlyrics.ui.components.canAnimateFloatingControl
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.floatingPanelEaseOut
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.bindAppearance
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal data class FloatingPreviewCardHandle(
    val cardView: View,
    val lyricTextView: TextView,
    val bodyView: View,
    val updateText: (CharSequence) -> Unit,
    val isExpanded: () -> Boolean,
    val expansionFraction: () -> Float
) {
    fun isPreviewVisible(): Boolean =
        isExpanded() && expansionFraction() == 1f && bodyView.isPreviewFullyVisible()
}

internal fun MainUiHost.createFloatingPreviewCard(
    isExpanded: () -> Boolean,
    setExpanded: (Boolean) -> Unit,
    style: () -> FloatingLyricsStyle,
    isWordByWordLyricsEnabled: () -> Boolean,
    plainPreviewText: () -> CharSequence,
    wordByWordPreviewText: () -> CharSequence,
    onVisibilityChanged: () -> Unit = {},
    onExpansionFrame: () -> Unit = {}
): FloatingPreviewCardHandle {
    lateinit var lyricView: TextView
    lateinit var lyricBody: ScrollView
    lateinit var toggleView: ImageButton
    val toggleSize = dp(FloatingPageTokens.PREVIEW_TOGGLE_TOUCH_SIZE_DP)
    val toggleInset = (toggleSize - dp(FloatingPageTokens.PREVIEW_TOGGLE_VISIBLE_SIZE_DP)) / 2
    val toggleSurface = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    val revealClip = Rect()
    val rise = dp(FloatingPageTokens.PREVIEW_SLIDE_Y_DP)
    var compactExpanded = interactions.read("floating.preview.compact")?.getBoolean("expanded") ?: false
    var renderedExpanded: Boolean? = null
    var expansionFraction = 0f
    var expansionAnimator: ValueAnimator? = null

    fun expanded() = if (windowLayout.compact) compactExpanded else isExpanded()

    fun renderExpansion() {
        lyricBody.alpha = expansionFraction
        lyricBody.translationY = -rise * (1f - expansionFraction)
        val revealedHeight = (lyricBody.height * expansionFraction).roundToInt()
        revealClip.set(0, 0, lyricBody.width, revealedHeight)
        lyricBody.clipBounds = if (expansionFraction == 1f) null else revealClip
        toggleView.rotation = 90f - 180f * expansionFraction
        toggleSurface.alpha = (255 * (1f - expansionFraction)).roundToInt()
    }

    fun settleExpansion(fraction: Float) {
        expansionFraction = fraction
        lyricBody.visibility = if (fraction == 1f) View.VISIBLE else View.GONE
        renderExpansion()
        onExpansionFrame()
    }

    fun updateExpansion(next: Boolean, animate: Boolean) {
        expansionAnimator?.apply {
            removeAllUpdateListeners()
            removeAllListeners()
            cancel()
        }
        expansionAnimator = null
        renderedExpanded = next
        toggleView.contentDescription = getString(if (next) R.string.ui_collapse_preview else R.string.ui_expand_preview)
        val target = if (next) 1f else 0f
        if (!animate || expansionFraction == target) {
            settleExpansion(target)
            return
        }

        lyricBody.visibility = View.VISIBLE
        val fullDuration = if (next) FloatingPageTokens.PREVIEW_EXPAND_MS else FloatingPageTokens.PREVIEW_COLLAPSE_MS
        val animator = ValueAnimator.ofFloat(expansionFraction, target).apply {
            duration = (fullDuration * abs(target - expansionFraction)).roundToLong().coerceAtLeast(1L)
            interpolator = floatingPanelEaseOut
            addUpdateListener { animator ->
                expansionFraction = animator.animatedValue as Float
                renderExpansion()
                onExpansionFrame()
            }
            doOnEnd {
                expansionAnimator = null
                settleExpansion(target)
                onVisibilityChanged()
            }
        }
        expansionAnimator = animator
        animator.start()
    }

    val card = object : LinearLayout(this) {
        private val scrollListener = ViewTreeObserver.OnScrollChangedListener(onVisibilityChanged)
        private var scrollObserver: ViewTreeObserver? = null

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            scrollObserver = viewTreeObserver.also { it.addOnScrollChangedListener(scrollListener) }
            onVisibilityChanged()
        }

        override fun onDetachedFromWindow() {
            scrollObserver?.takeIf { it.isAlive }?.removeOnScrollChangedListener(scrollListener)
            scrollObserver = null
            super.onDetachedFromWindow()
            if (expansionAnimator != null) updateExpansion(expanded(), animate = false)
            onVisibilityChanged()
        }

        override fun onVisibilityAggregated(isVisible: Boolean) {
            super.onVisibilityAggregated(isVisible)
            if (!isVisible && expansionAnimator != null) updateExpansion(expanded(), animate = false)
            onVisibilityChanged()
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            renderExpansion()
            onVisibilityChanged()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val expanded = expanded()
            if (renderedExpanded != expanded) updateExpansion(expanded, animate = false)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }.apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.TOP
        minimumHeight = toggleSize
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lyricView = floatingPreviewText(
            if (isWordByWordLyricsEnabled()) wordByWordPreviewText() else plainPreviewText(),
            style()
        ).apply {
            maxLines = Int.MAX_VALUE
            includeFontPadding = false
        }
        lyricBody = object : ScrollView(this@createFloatingPreviewCard) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                    expansionFraction < 1f && event.y >= revealClip.bottom
                ) return false
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isScrollbarFadingEnabled = false
            addView(
                lyricView,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        addView(lyricBody)

        toggleView = ImageButton(this@createFloatingPreviewCard).apply {
            layoutParams = LinearLayout.LayoutParams(toggleSize, toggleSize)
            background = InsetDrawable(toggleSurface, toggleInset)
            setPadding(0, 0, 0, 0)
            scaleType = ImageView.ScaleType.CENTER
            setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_air_chevron_right)?.mutate()?.apply {
                isAutoMirrored = false
            })
            bindAppearance(this) { button ->
                button.imageTintList = ColorStateList.valueOf(colorTextMuted)
                toggleSurface.setColor(AirColorUtils.withAlpha(colorSurfaceLight, 153))
            }
            enableSoftPressFeedback(0.92f)
            setOnClickListener {
                val next = !expanded()
                if (windowLayout.compact) {
                    compactExpanded = next
                    interactions.write("floating.preview.compact") { putBoolean("expanded", next) }
                } else {
                    setExpanded(next)
                }
                updateExpansion(next, animate = canAnimateFloatingControl())
                onVisibilityChanged()
            }
        }
        addView(toggleView)
    }

    return FloatingPreviewCardHandle(
        cardView = card,
        lyricTextView = lyricView,
        bodyView = lyricBody,
        updateText = { updateFloatingPreviewText(lyricView, it) },
        isExpanded = ::expanded,
        expansionFraction = { expansionFraction }
    )
}
