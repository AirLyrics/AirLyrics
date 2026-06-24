package com.andsi.airlyrics.ui.pages.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.tokens.AirUiTokens

internal class InteractiveLogoView(
    activity: MainUiHost,
    private val onExtraClick: () -> Unit
) : LinearLayout(activity) {
    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(activity.dp(AirUiTokens.Space.Sm), activity.dp(AirUiTokens.Space.Sm), activity.dp(AirUiTokens.Space.Sm), activity.dp(AirUiTokens.Space.Sm))

        addView(ImageView(activity).apply {
            setImageResource(R.drawable.airlyrics_launcher_foreground)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        })

        isClickable = true
        isFocusable = true
        setOnClickListener {
            bounce(activity)
            onExtraClick()
        }
    }

    private fun bounce(activity: MainUiHost) {
        animate().cancel()
        animate()
            .scaleX(AboutTokens.LogoPressScale)
            .scaleY(AboutTokens.LogoPressScale)
            .translationY(activity.dp(AirUiTokens.Space.Xs).toFloat())
            .setDuration(AboutTokens.LogoPressMs)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                animate()
                    .scaleX(AboutTokens.LogoOvershootScale)
                    .scaleY(AboutTokens.LogoOvershootScale)
                    .translationY(-activity.dp(AirUiTokens.Space.Sm).toFloat())
                    .setDuration(AboutTokens.LogoOvershootMs)
                    .setInterpolator(OvershootInterpolator(AboutTokens.LogoOvershoot))
                    .withEndAction {
                        animate()
                            .scaleX(AirUiTokens.Motion.RestScale)
                            .scaleY(AirUiTokens.Motion.RestScale)
                            .translationY(0f)
                            .setDuration(AboutTokens.LogoSettleMs)
                            .setInterpolator(OvershootInterpolator(AboutTokens.LogoSettleOvershoot))
                            .start()
                    }
                    .start()
            }
            .start()
    }
}

internal class EasterEggOverlay(activity: MainUiHost) : FrameLayout(activity) {
    private var clickCount = 0
    private val segmentViews = mutableListOf<VerticalMessageSegment>()

    init {
        clipChildren = false
        clipToPadding = false

        val leftGroup = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        val rightGroup = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }

        val segment1 = VerticalMessageSegment(
            activity,
            text = activity.getString(R.string.ui_like_it).toString(),
            colors = listOf(
                Color.rgb(255, 244, 250),
                Color.rgb(255, 210, 228)
            )
        )
        val segment2 = VerticalMessageSegment(
            activity,
            text = activity.getString(R.string.ui_give_it_a_star).toString(),
            colors = listOf(
                Color.rgb(255, 188, 200),
                Color.rgb(255, 152, 170)
            )
        )
        val segment3 = VerticalMessageSegment(
            activity,
            text = activity.getString(R.string.ui_yay).toString(),
            colors = listOf(
                Color.rgb(255, 238, 150),
                Color.rgb(145, 203, 255)
            )
        )

        segmentViews += listOf(segment1, segment2, segment3)

        leftGroup.addView(segment1.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
        leftGroup.addView(segment2.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = activity.dp(AboutTokens.SegmentGapDp)
            }
        })
        rightGroup.addView(segment3.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })

        addView(leftGroup, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.TOP
        ).apply {
            leftMargin = activity.dp(AboutTokens.SegmentSideMarginDp)
            topMargin = activity.dp(AirUiTokens.Space.Xxl)
        })

        addView(rightGroup, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.TOP
        ).apply {
            rightMargin = activity.dp(AboutTokens.SegmentSideMarginDp)
            topMargin = activity.dp(AirUiTokens.Space.Xxl)
        })
    }

    fun onLogoClicked() {
        clickCount += 1
        val unlockedIndex = when {
            clickCount >= 11 -> 2
            clickCount >= 6 -> 1
            clickCount >= 1 -> 0
            else -> -1
        }
        for (index in 0..unlockedIndex) {
            segmentViews.getOrNull(index)?.reveal()
        }
    }
}

private class VerticalMessageSegment(
    activity: MainUiHost,
    text: String,
    private val colors: List<Int>
) : LinearLayout(activity) {
    private val charViews = mutableListOf<TextView>()
    private var revealed = false

    init {
        orientation = VERTICAL
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        text.forEachIndexed { index, ch ->
            val textView = TextView(activity).apply {
                this.text = ch.toString()
                textSize = AboutTokens.SegmentTextSp
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)
                setTextColor(colors[index % colors.size])
                alpha = 0f
                translationY = activity.dp(AirUiTokens.Space.Xl).toFloat()
                rotation = when (index % 4) {
                    0 -> -7f
                    1 -> 5f
                    2 -> -4f
                    else -> 6f
                }
                setPadding(0, 0, 0, activity.dp(AirUiTokens.Stroke.Hairline))
                includeFontPadding = false
                letterSpacing = AboutTokens.SegmentLetterSpacing
                setShadowLayer(AboutTokens.SegmentShadowRadius, 0f, 0f, Color.argb(AboutTokens.SegmentShadowAlpha, 255, 255, 255))
            }
            charViews += textView
            addView(textView)
        }
    }

    fun isRevealed(): Boolean = revealed

    fun reveal() {
        if (revealed) return
        revealed = true
        charViews.forEachIndexed { index, textView ->
            textView.animate()
                                .alpha(AirUiTokens.Motion.RestAlpha)
                .translationY(0f)
                .setStartDelay(index * AboutTokens.SegmentRevealDelayMs)
                .setDuration(AboutTokens.SegmentRevealMs)
                .setInterpolator(DecelerateInterpolator())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        startFloatingJitter(textView, index)
                    }
                })
                .start()
        }
    }

    private fun startFloatingJitter(view: TextView, index: Int) {
        val direction = if (index % 2 == 0) 1f else -1f
        ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, 0f - AboutTokens.SegmentJitterDistance * direction, 0f).apply {
            duration = AboutTokens.SegmentJitterBaseMs + index * AboutTokens.SegmentJitterStepMs
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = DecelerateInterpolator()
            startDelay = index * AboutTokens.SegmentJitterDelayStepMs
            start()
        }
        ObjectAnimator.ofFloat(view, View.ROTATION, view.rotation, view.rotation + AboutTokens.SegmentRotateDistance * direction, view.rotation).apply {
            duration = AboutTokens.SegmentRotateBaseMs + index * AboutTokens.SegmentRotateStepMs
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = DecelerateInterpolator()
            startDelay = index * AboutTokens.SegmentRotateDelayStepMs
            start()
        }
    }
}
