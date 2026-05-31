package com.andsi.airlyrics.ui.pages.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.andsi.airlyrics.app.MainActivity
import com.andsi.airlyrics.app.getAppVersionName
import com.andsi.airlyrics.app.openUrl
import com.andsi.airlyrics.app.settingsBackHeader
import com.andsi.airlyrics.ui.components.bigText
import com.andsi.airlyrics.ui.components.card
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.playTinyPulse
import com.andsi.airlyrics.ui.components.scroll
import com.andsi.airlyrics.ui.components.showAirDialog
import com.andsi.airlyrics.ui.components.statusPill
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentSoft
import com.andsi.airlyrics.ui.theme.colorCard
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import com.andsi.airlyrics.ui.tokens.AirUiTokens


private object AboutTokens {
    const val HeaderBottomMarginDp = 18
    const val HeaderMinHeightDp = 260
    const val LogoSizeDp = 220
    const val GithubButtonWidthDp = 42
    const val GithubButtonHeightDp = 32
    const val GithubIconSizeDp = 20
    const val ChangeLogHorizontalPaddingDp = 28
    const val ChangeLogVerticalPaddingDp = 11
    const val LogoPressScale = 0.9f
    const val LogoOvershootScale = 1.1f
    const val LogoPressMs = 55L
    const val LogoOvershootMs = 115L
    const val LogoSettleMs = 160L
    const val LogoOvershoot = 1.55f
    const val LogoSettleOvershoot = 1.15f
    const val SegmentTextSp = 17f
    const val SegmentTopMarginDp = 10
    const val SegmentSideMarginDp = 22
    const val SegmentGapDp = 8
    const val SegmentRevealDelayMs = 120L
    const val SegmentRevealMs = 260L
    const val SegmentJitterBaseMs = 1800L
    const val SegmentJitterStepMs = 80L
    const val SegmentRotateBaseMs = 1600L
    const val SegmentRotateStepMs = 60L
    const val SegmentJitterDelayStepMs = 40L
    const val SegmentRotateDelayStepMs = 45L
    const val SegmentJitterDistance = 3f
    const val SegmentRotateDistance = 2f
    const val SegmentLetterSpacing = 0.04f
    const val SegmentShadowRadius = 10f
    const val SegmentShadowAlpha = 68
}

internal fun createAboutSettingsPage(activity: MainActivity): View = with(activity) {
    val container = com.andsi.airlyrics.ui.components.pageContainer(activity)
    container.addView(settingsBackHeader(getString(R.string.ui_about)))
    container.addView(aboutLogoHeader())
    container.addView(changeLogButton())
    return scroll(activity, container)
}

private fun MainActivity.aboutLogoHeader(): View {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 0, 0, dp(AboutTokens.HeaderBottomMarginDp))
        layoutParams = params
        setPadding(0, dp(AirUiTokens.Space.Xxs), 0, dp(AirUiTokens.Space.Lg))

        val easterEggOverlay = EasterEggOverlay(activity)

        addView(FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            minimumHeight = dp(AboutTokens.HeaderMinHeightDp)

            addView(InteractiveLogoView(activity) {
                easterEggOverlay.onLogoClicked()
            }.apply {
                layoutParams = FrameLayout.LayoutParams(dp(AboutTokens.LogoSizeDp), dp(AboutTokens.LogoSizeDp), Gravity.CENTER)
            })

            addView(easterEggOverlay.apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            })
        })

        addView(bigText(activity, "AirLyrics").apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(AirUiTokens.Space.Xl), 0, 0)
        })

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(AirUiTokens.Space.Xxl), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            addView(statusPill(activity, getAppVersionName(), playing = true).apply {
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, dp(AirUiTokens.Space.Xl), 0)
                layoutParams = lp
            })

            addView(githubIconButton(activity))
        })
    }
}

private fun MainActivity.githubIconButton(activity: MainActivity): View {
    return FrameLayout(activity).apply {
        layoutParams = LinearLayout.LayoutParams(dp(AboutTokens.GithubButtonWidthDp), dp(AboutTokens.GithubButtonHeightDp))
        background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Pill).toFloat()
            setColor(colorSurfaceLight)
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorAccentSoft)
        }
        isClickable = true
        isFocusable = true
        enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
        setOnClickListener {
            playTinyPulse(this)
            openUrl("https://github.com/AirLyrics/AirLyrics")
        }

        addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_air_github)
            setColorFilter(colorAccent)
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = FrameLayout.LayoutParams(dp(AboutTokens.GithubIconSizeDp), dp(AboutTokens.GithubIconSizeDp), Gravity.CENTER)
            contentDescription = "GitHub"
        })
    }
}

private fun MainActivity.changeLogButton(): View {
    val activity = this
    return TextView(activity).apply {
        text = getString(R.string.ui_changelog)
        gravity = Gravity.CENTER
        textSize = AirUiTokens.TextSize.Button
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(colorAccent)
        setPadding(dp(AboutTokens.ChangeLogHorizontalPaddingDp), dp(AboutTokens.ChangeLogVerticalPaddingDp), dp(AboutTokens.ChangeLogHorizontalPaddingDp), dp(AboutTokens.ChangeLogVerticalPaddingDp))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, 0, 0, dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs))
        }
        background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Pill).toFloat()
            setColor(colorSurfaceLight)
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorAccentSoft)
        }
        isClickable = true
        isFocusable = true
        enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale + 0.01f)
        setOnClickListener {
            playTinyPulse(this)
            showUpdateLogDialog()
        }
    }
}

private fun MainActivity.showUpdateLogDialog() {
    showAirDialog(
        title = getString(R.string.ui_changelog),
        message = null,
        positiveText = getString(R.string.ui_ok),
        body = {
            addView(TextView(this@showUpdateLogDialog).apply {
                text = loadChangelogText()
                textSize = AirUiTokens.TextSize.Body
                setTextColor(colorTextMuted)
                setLineSpacing(dp(AirUiTokens.Space.Sm).toFloat(), 1f)
                setPadding(0, dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), 0, dp(AirUiTokens.Space.Sm))
            })
        }
    )
}

private fun MainActivity.loadChangelogText(): String {
    val fallback = getString(R.string.ui_no_changelog_yet)
    return runCatching {
        assets.open("changelog.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrDefault(fallback)
        .trim()
        .ifBlank { fallback }
}

private class InteractiveLogoView(
    activity: MainActivity,
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

    private fun bounce(activity: MainActivity) {
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

private class EasterEggOverlay(activity: MainActivity) : FrameLayout(activity) {
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
    activity: MainActivity,
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
