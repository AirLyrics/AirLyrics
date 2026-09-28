package com.andsi.airlyrics.ui.pages.settings

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.PointF
import com.andsi.airlyrics.ui.components.AdaptiveGridLayout
import android.view.Gravity
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.color.AirColorUtils
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentLight
import com.andsi.airlyrics.ui.theme.colorOnAccent
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorText

internal fun orderedLyricsSourceOptions(
    selectedSources: List<PlainLyricsSearchSource>,
    sourceOptions: List<PlainLyricsSearchSource>
): List<PlainLyricsSearchSource> {
    val options = sourceOptions.distinct()
    val selected = selectedSources.distinct().filter { it in options }
    return selected + options.filterNot { it in selected }
}

@SuppressLint("ViewConstructor")
internal class LyricsSourceOptionButton(
    private val host: MainUiHost,
    val source: PlainLyricsSearchSource,
    compactTitle: String
) : FrameLayout(host) {
    private val titleView = TextView(host).apply {
        text = compactTitle
        gravity = Gravity.CENTER
        textSize = AirUiTokens.TextSize.BodySmall
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val priorityLabel = TextView(host).apply {
        gravity = Gravity.CENTER
        textSize = AirUiTokens.TextSize.Tiny
        typeface = Typeface.DEFAULT
        includeFontPadding = false
        setTextColor(AirColorUtils.withAlpha(host.colorOnAccent, PRIORITY_LABEL_ALPHA))
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val contentRow = LinearLayout(host).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        tag = source
        isClickable = true
        isFocusable = true
        clipChildren = false
        clipToPadding = false
        enableSoftPressFeedback(AirUiTokens.Motion.OptionPressScale)

        contentRow.addView(
            priorityLabel,
            LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            )
        )
        contentRow.addView(
            titleView,
            LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            )
        )
        addView(
            contentRow,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    fun render(
        selectedPriority: Int?,
        fullTitle: String
    ) {
        val selected = selectedPriority != null
        isSelected = selected
        contentDescription = fullTitle
        titleView.setTextColor(if (selected) host.colorOnAccent else host.colorText)
        priorityLabel.apply {
            text = selectedPriority?.toString().orEmpty()
            visibility = if (selected) VISIBLE else GONE
        }
        titleView.layoutParams = (titleView.layoutParams as LinearLayout.LayoutParams).apply {
            marginStart = host.dp(if (selected) AirUiTokens.Space.Sm else 0)
        }
        background = GradientDrawable().apply {
            cornerRadius = host.dp(AirUiTokens.Radius.Md).toFloat()
            setColor(if (selected) host.colorAccent else host.colorSurfaceLight)
            setStroke(
                host.dp(if (selected) AirUiTokens.Stroke.Selected else AirUiTokens.Stroke.Hairline),
                if (selected) host.colorAccentLight else host.colorStroke
            )
        }
        ViewCompat.setStateDescription(
            this,
            if (selectedPriority != null) {
                host.getString(R.string.ui_lyrics_source_selected_priority_state, selectedPriority)
            } else {
                host.getString(R.string.ui_lyrics_source_not_selected_state)
            }
        )
    }
}

private const val PRIORITY_LABEL_ALPHA = 160

@SuppressLint("ViewConstructor")
internal class LyricsSourceOrderRow(
    private val host: MainUiHost
) : AdaptiveGridLayout(host, PlainLyricsSearchSource.onlineSources.size, host.dp(AirUiTokens.Space.Lg), host.dp(AirUiTokens.Space.Lg)) {
    private var reorderAnimator: AnimatorSet? = null
    private var pendingPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var reorderGeneration = 0L

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = host.dp(AirUiTokens.Space.Xl)
        }
    }

    fun setSourceOrder(
        orderedButtons: List<LyricsSourceOptionButton>,
        animate: Boolean
    ) {
        require(orderedButtons.distinct().size == orderedButtons.size) {
            "Lyrics source buttons must be unique"
        }
        orderedButtons.forEach { button ->
            require(button.parent == null || button.parent === this) {
                "Lyrics source button already belongs to another parent"
            }
        }

        val currentOrder = (0 until childCount).map { index -> getChildAt(index) }
        if (currentOrder == orderedButtons) {
            prepareButtonLayout(orderedButtons)
            if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
                cancelPendingReorder(settle = true)
            }
            return
        }

        val oldVisualPositions = orderedButtons
            .filter { it.parent === this }
            .associateWith { PointF(it.x, it.y) }
        val shouldAnimate = animate &&
            isAttachedToWindow &&
            isLaidOut &&
            width > 0 &&
            ValueAnimator.areAnimatorsEnabled() &&
            oldVisualPositions.size == orderedButtons.size

        cancelPendingReorder(settle = false)

        orderedButtons.filter { it.parent == null }.forEach(::addView)
        orderedButtons.forEach(::bringChildToFront)
        prepareButtonLayout(orderedButtons)

        if (!shouldAnimate) {
            settleButtons()
            return
        }

        val generation = ++reorderGeneration
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (viewTreeObserver.isAlive) {
                    viewTreeObserver.removeOnPreDrawListener(this)
                }
                if (pendingPreDrawListener === this) {
                    pendingPreDrawListener = null
                }
                if (generation != reorderGeneration) {
                    return true
                }
                if (!isAttachedToWindow) {
                    settleButtons()
                    return true
                }
                startReorderAnimation(orderedButtons, oldVisualPositions, generation)
                return true
            }
        }
        pendingPreDrawListener = listener
        viewTreeObserver.addOnPreDrawListener(listener)
        requestLayout()
    }

    internal fun displayedSources(): List<PlainLyricsSearchSource> =
        (0 until childCount).map { index ->
            (getChildAt(index) as LyricsSourceOptionButton).source
        }

    override fun onDetachedFromWindow() {
        cancelPendingReorder(settle = true)
        super.onDetachedFromWindow()
    }

    private fun prepareButtonLayout(buttons: List<LyricsSourceOptionButton>) {
        buttons.forEach { button ->
            button.layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
            button.minimumHeight = host.dp(AirUiTokens.Layout.LyricsSourceButtonHeight)
            button.setPadding(host.dp(AirUiTokens.Space.Lg), host.dp(AirUiTokens.Space.Sm), host.dp(AirUiTokens.Space.Lg), host.dp(AirUiTokens.Space.Sm))
        }
    }

    private fun startReorderAnimation(
        buttons: List<LyricsSourceOptionButton>,
        oldVisualPositions: Map<LyricsSourceOptionButton, PointF>,
        generation: Long
    ) {
        val animators = buttons.flatMap { button ->
            val old = oldVisualPositions.getValue(button)
            val dx = old.x - button.left
            val dy = old.y - button.top
            button.translationX = dx
            button.translationY = dy
            listOfNotNull(
                ObjectAnimator.ofFloat(button, TRANSLATION_X, dx, 0f).takeIf { kotlin.math.abs(dx) >= 0.5f },
                ObjectAnimator.ofFloat(button, TRANSLATION_Y, dy, 0f).takeIf { kotlin.math.abs(dy) >= 0.5f }
            )
        }
        if (animators.isEmpty()) {
            settleButtons()
            return
        }

        reorderAnimator = AnimatorSet().apply {
            duration = AirUiTokens.Motion.LyricsSourceReorderMs
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            playTogether(animators)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (reorderAnimator !== animation || generation != reorderGeneration) return
                    reorderAnimator = null
                    settleButtons()
                }
            })
            start()
        }
    }

    private fun cancelPendingReorder(settle: Boolean) {
        reorderGeneration++
        pendingPreDrawListener?.let { listener ->
            if (viewTreeObserver.isAlive) {
                viewTreeObserver.removeOnPreDrawListener(listener)
            }
        }
        pendingPreDrawListener = null
        reorderAnimator?.removeAllListeners()
        reorderAnimator?.cancel()
        reorderAnimator = null
        if (settle) settleButtons()
    }

    private fun settleButtons() {
        for (index in 0 until childCount) {
            getChildAt(index).translationX = 0f
            getChildAt(index).translationY = 0f
        }
    }
}
