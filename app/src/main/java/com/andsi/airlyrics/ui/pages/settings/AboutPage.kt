package com.andsi.airlyrics.ui.pages.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.doOnPreDraw
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.components.bigText
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.playTinyPulse
import com.andsi.airlyrics.ui.components.scroll
import com.andsi.airlyrics.ui.theme.colorAccentText
import com.andsi.airlyrics.ui.theme.colorAccentSoft
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.design.tokens.AirUiTokens



// Motion Prompt #002 · MIT License (c) 2026 Shohei Ohtaki · https://showheyohtaki.github.io/motion-prompt/#002
// Motion Prompt #005 · MIT License (c) 2026 Shohei Ohtaki · https://showheyohtaki.github.io/motion-prompt/#005
// Motion Prompt #049 · MIT License (c) 2026 Shohei Ohtaki · https://showheyohtaki.github.io/motion-prompt/#049
internal fun createAboutSettingsPage(activity: MainUiHost, animateChildren: Boolean): View = with(activity) {
    val container = com.andsi.airlyrics.ui.components.pageContainer(activity)
    val dragOverlay = MascotDragOverlay(activity)
    val aboutHeader = aboutLogoHeader(dragOverlay)
    container.addView(settingsBackHeader(getString(R.string.ui_about)))
    container.addView(aboutHeader)
    val page = scroll(activity, container, animateChildren = false)
    if (animateChildren) {
        // The first child contains the interactive logo and its Easter Egg; leave it untouched.
        revealAboutContent(page, aboutHeader.getChildAt(1), aboutHeader.getChildAt(2))
    }
    return FrameLayout(activity).apply {
        clipChildren = false
        addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(dragOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}

private fun MainUiHost.revealAboutContent(page: View, title: View, details: View) {
    if (!ValueAnimator.areAnimatorsEnabled()) return
    val titleOffset = dp(8).toFloat()
    val contentOffset = dp(6).toFloat()
    title.clipBounds = Rect()
    title.translationY = titleOffset
    details.alpha = 0f
    details.translationY = contentOffset
    var titleAnimator: ValueAnimator? = null

    fun settle() {
        titleAnimator?.cancel()
        titleAnimator = null
        title.clipBounds = null
        title.translationY = 0f
        details.animate().cancel()
        details.animate().startDelay = 0L
        details.alpha = 1f
        details.translationY = 0f
    }

    page.addOnAttachStateChangeListener(
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = settle()
        },
    )
    page.doOnPreDraw {
        if ((!page.isAttachedToWindow) || (!ValueAnimator.areAnimatorsEnabled()) || (title.height == 0)) {
            settle()
            return@doOnPreDraw
        }
        titleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = 95L
            duration = 180L
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                title.clipBounds = Rect(0, 0, title.width, (title.height * progress).toInt())
                title.translationY = titleOffset * (1f - progress)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    title.clipBounds = null
                    title.translationY = 0f
                    titleAnimator = null
                }
            })
            start()
        }
        details.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(135L)
            .setDuration(165L)
            .setInterpolator(DecelerateInterpolator(1.2f))
            .withEndAction { details.animate().setStartDelay(0L) }
            .start()
    }
}

private fun MainUiHost.aboutLogoHeader(dragOverlay: MascotDragOverlay): LinearLayout {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 0, 0, dp(AboutTokens.HEADER_BOTTOM_MARGIN_DP))
        layoutParams = params
        setPadding(0, dp(AirUiTokens.Space.Xxs), 0, dp(AirUiTokens.Space.Lg))

        val easterEggOverlay = EasterEggOverlay(activity)

        addView(FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            minimumHeight = dp(AboutTokens.HEADER_MIN_HEIGHT_DP)

            addView(InteractiveLogoView(activity, dragOverlay) {
                easterEggOverlay.onLogoClicked()
            }.apply {
                layoutParams = FrameLayout.LayoutParams(dp(AboutTokens.LOGO_SIZE_DP), dp(AboutTokens.LOGO_SIZE_DP), Gravity.CENTER)
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

            addView(aboutVersionEntry(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(AirUiTokens.Space.Xl)
            })

            addView(githubIconButton(activity, dragOverlay))
        })
    }
}

private fun MainUiHost.githubIconButton(activity: MainUiHost, dragOverlay: MascotDragOverlay): View {
    return FrameLayout(activity).apply {
        dragOverlay.setGithubTarget(this)
        layoutParams = LinearLayout.LayoutParams(dp(AboutTokens.GITHUB_BUTTON_WIDTH_DP), dp(AboutTokens.GITHUB_BUTTON_HEIGHT_DP))
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
            setColorFilter(colorAccentText)
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = FrameLayout.LayoutParams(dp(AboutTokens.GITHUB_ICON_SIZE_DP), dp(AboutTokens.GITHUB_ICON_SIZE_DP), Gravity.CENTER)
            contentDescription = "GitHub"
        })
    }
}
