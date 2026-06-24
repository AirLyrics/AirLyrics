package com.andsi.airlyrics.ui.pages.settings

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.tokens.AirUiTokens

@SuppressLint("ViewConstructor")
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
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
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
            .scaleX(AboutTokens.LOGO_PRESS_SCALE)
            .scaleY(AboutTokens.LOGO_PRESS_SCALE)
            .translationY(activity.dp(AirUiTokens.Space.Xs).toFloat())
            .setDuration(AboutTokens.LOGO_PRESS_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                animate()
                    .scaleX(AboutTokens.LOGO_OVERSHOOT_SCALE)
                    .scaleY(AboutTokens.LOGO_OVERSHOOT_SCALE)
                    .translationY(-activity.dp(AirUiTokens.Space.Sm).toFloat())
                    .setDuration(AboutTokens.LOGO_OVERSHOOT_MS)
                    .setInterpolator(OvershootInterpolator(AboutTokens.LOGO_OVERSHOOT))
                    .withEndAction {
                        animate()
                            .scaleX(AirUiTokens.Motion.RestScale)
                            .scaleY(AirUiTokens.Motion.RestScale)
                            .translationY(0f)
                            .setDuration(AboutTokens.LOGO_SETTLE_MS)
                            .setInterpolator(OvershootInterpolator(AboutTokens.LOGO_SETTLE_OVERSHOOT))
                            .start()
                    }
                    .start()
            }
            .start()
    }
}

@SuppressLint("ViewConstructor")
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
            text = activity.getString(R.string.ui_like_it),
            colors = listOf(
                Color.rgb(255, 244, 250),
                Color.rgb(255, 210, 228)
            )
        )
        val segment2 = VerticalMessageSegment(
            activity,
            text = activity.getString(R.string.ui_give_it_a_star),
            colors = listOf(
                Color.rgb(255, 188, 200),
                Color.rgb(255, 152, 170)
            )
        )
        val segment3 = VerticalMessageSegment(
            activity,
            text = activity.getString(R.string.ui_yay),
            colors = listOf(
                Color.rgb(255, 238, 150),
                Color.rgb(145, 203, 255)
            )
        )

        segmentViews += listOf(segment1, segment2, segment3)

        leftGroup.addView(segment1.apply {
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            )
        })
        leftGroup.addView(segment2.apply {
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = activity.dp(AboutTokens.SEGMENT_GAP_DP)
            }
        })
        rightGroup.addView(segment3.apply {
            layoutParams = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
            )
        })

        addView(leftGroup, LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.TOP
        ).apply {
            leftMargin = activity.dp(AboutTokens.SEGMENT_SIDE_MARGIN_DP)
            topMargin = activity.dp(AirUiTokens.Space.Xxl)
        })

        addView(rightGroup, LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.TOP
        ).apply {
            rightMargin = activity.dp(AboutTokens.SEGMENT_SIDE_MARGIN_DP)
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

@SuppressLint("ViewConstructor")
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
                textSize = AboutTokens.SEGMENT_TEXT_SP
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
                letterSpacing = AboutTokens.SEGMENT_LETTER_SPACING
                setShadowLayer(AboutTokens.SEGMENT_SHADOW_RADIUS, 0f, 0f, Color.argb(AboutTokens.SEGMENT_SHADOW_ALPHA, 255, 255, 255))
            }
            charViews += textView
            addView(textView)
        }
    }

    fun reveal() {
        if (revealed) return
        revealed = true
        charViews.forEachIndexed { index, textView ->
            textView.animate()
                                .alpha(AirUiTokens.Motion.RestAlpha)
                .translationY(0f)
                .setStartDelay(index * AboutTokens.SEGMENT_REVEAL_DELAY_MS)
                .setDuration(AboutTokens.SEGMENT_REVEAL_MS)
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
        ObjectAnimator.ofFloat(view, TRANSLATION_Y, 0f, 0f - AboutTokens.SEGMENT_JITTER_DISTANCE * direction, 0f).apply {
            duration = AboutTokens.SEGMENT_JITTER_BASE_MS + index * AboutTokens.SEGMENT_JITTER_STEP_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = DecelerateInterpolator()
            startDelay = index * AboutTokens.SEGMENT_JITTER_DELAY_STEP_MS
            start()
        }
        ObjectAnimator.ofFloat(view, ROTATION, view.rotation, view.rotation + AboutTokens.SEGMENT_ROTATE_DISTANCE * direction, view.rotation).apply {
            duration = AboutTokens.SEGMENT_ROTATE_BASE_MS + index * AboutTokens.SEGMENT_ROTATE_STEP_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = DecelerateInterpolator()
            startDelay = index * AboutTokens.SEGMENT_ROTATE_DELAY_STEP_MS
            start()
        }
    }
}
