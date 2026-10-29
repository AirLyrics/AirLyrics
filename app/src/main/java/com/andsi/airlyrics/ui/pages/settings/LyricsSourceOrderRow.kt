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
import android.widget.HorizontalScrollView
import android.view.Gravity
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.PlainLyricsSearchSource
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.AirTonalButtonTone
import com.andsi.airlyrics.ui.components.airTonalButtonStyle
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.colorTextStrong

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
    compactTitle: String,
    private val spicyConfigured: () -> Boolean = { com.andsi.airlyrics.core.model.LyricsSourceCapability.spicyConfigured(com.andsi.airlyrics.BuildConfig.SPICY_CLIENT_KEY) }
) : FrameLayout(host) {
    private var selectedPriority: Int? = null
    private val sourceTooltip = LyricsSourceTooltip(host, this)

    private val titleView = TextView(host).apply {
        text = compactTitle
        gravity = Gravity.CENTER
        textSize = AirUiTokens.TextSize.BodySmall
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        setSingleLine(true)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val priorityLabel = TextView(host).apply {
        gravity = Gravity.CENTER
        textSize = AirUiTokens.TextSize.Tiny
        typeface = Typeface.DEFAULT
        includeFontPadding = false
        setTextColor(host.colorTextStrong)
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
        setOnLongClickListener {
            sourceTooltip.show(contentDescription)
            true
        }

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
        fullTitle: String,
        deferHighlight: Boolean = false
    ) {
        this.selectedPriority = selectedPriority
        val configured = source != PlainLyricsSearchSource.SPICY || spicyConfigured()
        isEnabled = configured || selectedPriority != null
        alpha = if (configured) 1f else AirUiTokens.Button.DisabledAlpha
        val selected = selectedPriority != null
        contentDescription = fullTitle
        priorityLabel.apply {
            text = selectedPriority?.let {
                String.format(resources.configuration.locales[0], "%d", it)
            }.orEmpty()
            visibility = if (selected) VISIBLE else GONE
        }
        titleView.layoutParams = (titleView.layoutParams as LinearLayout.LayoutParams).apply {
            marginStart = host.dp(if (selected) AirUiTokens.Space.Sm else 0)
        }
        ViewCompat.setStateDescription(
            this,
            if (selectedPriority != null) {
                host.getString(R.string.ui_lyrics_source_selected_priority_state, selectedPriority)
            } else {
                host.getString(R.string.ui_lyrics_source_not_selected_state)
            }
        )
        applySelectionHighlight(selected && (!deferHighlight || isSelected))
    }

    internal fun settleSelectionHighlight() {
        applySelectionHighlight(selectedPriority != null)
    }

    private fun applySelectionHighlight(selected: Boolean) {
        isSelected = selected
        val tone = if (selected) AirTonalButtonTone.SOURCE_SELECTED else AirTonalButtonTone.DEFAULT
        val style = host.airTonalButtonStyle(tone)
        titleView.setTextColor(style.textColor)
        priorityLabel.setTextColor(style.textColor)
        // Reserve the final priority width before moving, without showing it early.
        priorityLabel.visibility = when {
            selectedPriority == null -> GONE
            selected -> VISIBLE
            else -> INVISIBLE
        }
        background = GradientDrawable().apply {
            cornerRadius = host.dp(AirUiTokens.Radius.Md).toFloat()
            setColor(style.fillColor)
            setStroke(host.dp(style.strokeWidthDp), style.strokeColor)
        }
    }
}

@SuppressLint("ViewConstructor")
internal class LyricsSourceOrderRow(
    private val host: MainUiHost
) : HorizontalScrollView(host) {
    private val sourceStrip = LinearLayout(host).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
    }
    private var reorderAnimator: AnimatorSet? = null
    private var pendingPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var reorderGeneration = 0L

    init {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        addView(sourceStrip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        clipChildren = true
        clipToPadding = false
        layoutParams = LinearLayout.LayoutParams(
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
            require(button.parent == null || button.parent === sourceStrip) {
                "Lyrics source button already belongs to another parent"
            }
        }

        val currentOrder = (0 until sourceStrip.childCount).map { index -> sourceStrip.getChildAt(index) }
        if (currentOrder == orderedButtons) {
            prepareButtonLayout(orderedButtons)
            if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
                cancelPendingReorder(settle = true)
            } else if (reorderAnimator == null && pendingPreDrawListener == null) {
                settleButtons()
            }
            return
        }

        val oldVisualPositions = orderedButtons
            .filter { it.parent === sourceStrip }
            .associateWith { PointF(it.x, it.y) }
        val shouldAnimate = animate &&
            isAttachedToWindow &&
            isLaidOut &&
            width > 0 &&
            ValueAnimator.areAnimatorsEnabled() &&
            oldVisualPositions.size == orderedButtons.size

        cancelPendingReorder(settle = false)

        currentOrder.filterNot { it in orderedButtons }.forEach(sourceStrip::removeView)
        orderedButtons.filter { it.parent == null }.forEach(sourceStrip::addView)
        orderedButtons.forEach(sourceStrip::bringChildToFront)
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
        (0 until sourceStrip.childCount).map { index ->
            (sourceStrip.getChildAt(index) as LyricsSourceOptionButton).source
        }

    override fun onDetachedFromWindow() {
        cancelPendingReorder(settle = true)
        super.onDetachedFromWindow()
    }

    private fun prepareButtonLayout(buttons: List<LyricsSourceOptionButton>) {
        buttons.forEachIndexed { index, button ->
            button.layoutParams = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = if (index < buttons.lastIndex) host.dp(AirUiTokens.Space.Lg) else 0 }
            button.minimumWidth = host.dp(76)
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
        for (index in 0 until sourceStrip.childCount) {
            sourceStrip.getChildAt(index).translationX = 0f
            sourceStrip.getChildAt(index).translationY = 0f
            (sourceStrip.getChildAt(index) as LyricsSourceOptionButton).settleSelectionHighlight()
        }
    }
}
