package com.andsi.airlyrics.ui.pages.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Dialog
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import com.andsi.airlyrics.ui.state.rememberAuxiliaryDialog
import com.andsi.airlyrics.ui.state.isAuxiliaryDialogShowing
import com.andsi.airlyrics.ui.state.forgetAuxiliaryDialog
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.components.bigText
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.playTinyPulse
import com.andsi.airlyrics.ui.components.scroll
import com.andsi.airlyrics.ui.components.showAirDialog
import com.andsi.airlyrics.ui.components.statusPill
import com.andsi.airlyrics.ui.components.styleAirTonalButton
import com.andsi.airlyrics.ui.theme.colorAccentText
import com.andsi.airlyrics.ui.theme.colorAccentSoft
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.design.tokens.AirUiTokens



// Motion Prompt #002 · MIT License (c) 2026 Shohei Ohtaki · https://showheyohtaki.github.io/motion-prompt/#002
// Motion Prompt #005 · MIT License (c) 2026 Shohei Ohtaki · https://showheyohtaki.github.io/motion-prompt/#005
// Motion Prompt #049 · MIT License (c) 2026 Shohei Ohtaki · https://showheyohtaki.github.io/motion-prompt/#049
internal fun createAboutSettingsPage(activity: MainUiHost, animateChildren: Boolean): View = with(activity) {
    val container = com.andsi.airlyrics.ui.components.pageContainer(activity)
    val aboutHeader = aboutLogoHeader()
    val changelogButton = changeLogButton()
    container.addView(settingsBackHeader(getString(R.string.ui_about)))
    container.addView(aboutHeader)
    container.addView(changelogButton)
    val page = scroll(activity, container, animateChildren = false)
    if (animateChildren) {
        // The first child contains the interactive logo and its Easter Egg; leave it untouched.
        revealAboutContent(page, aboutHeader.getChildAt(1), aboutHeader.getChildAt(2), changelogButton)
    }
    return page
}

private fun MainUiHost.revealAboutContent(page: View, title: View, details: View, changelog: View) {
    if (!ValueAnimator.areAnimatorsEnabled()) return
    val titleOffset = dp(8).toFloat()
    val contentOffset = dp(6).toFloat()
    title.clipBounds = Rect()
    title.translationY = titleOffset
    details.alpha = 0f
    details.translationY = contentOffset
    changelog.alpha = 0f
    changelog.translationY = contentOffset
    var titleAnimator: ValueAnimator? = null

    fun settle() {
        titleAnimator?.cancel()
        titleAnimator = null
        title.clipBounds = null
        title.translationY = 0f
        listOf(details, changelog).forEach {
            it.animate().cancel()
            it.animate().setStartDelay(0L)
            it.alpha = 1f
            it.translationY = 0f
        }
    }

    page.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) = settle()
    })
    page.doOnPreDraw {
        if (!page.isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled() || title.height == 0) {
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
        listOf(details, changelog).forEachIndexed { index, view ->
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(135L + index * 40L)
                .setDuration(if (index == 0) 165L else 155L)
                .setInterpolator(DecelerateInterpolator(1.2f))
                .withEndAction { view.animate().setStartDelay(0L) }
                .start()
        }
    }
}

private fun MainUiHost.aboutLogoHeader(): LinearLayout {
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

            addView(InteractiveLogoView(activity) {
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

private fun MainUiHost.githubIconButton(activity: MainUiHost): View {
    return FrameLayout(activity).apply {
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

private fun MainUiHost.changeLogButton(): View {
    val activity = this
    return TextView(activity).apply {
        setText(R.string.ui_changelog)
        gravity = Gravity.CENTER
        textSize = AirUiTokens.TextSize.Button
        typeface = Typeface.DEFAULT_BOLD
        minimumHeight = dp(AirUiTokens.Layout.IconTouchSize)
        setPadding(dp(AboutTokens.CHANGE_LOG_HORIZONTAL_PADDING_DP), dp(AboutTokens.CHANGE_LOG_VERTICAL_PADDING_DP), dp(AboutTokens.CHANGE_LOG_HORIZONTAL_PADDING_DP), dp(AboutTokens.CHANGE_LOG_VERTICAL_PADDING_DP))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, 0, 0, dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs))
        }
        styleAirTonalButton(this)
        isClickable = true
        isFocusable = true
        enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale + 0.01f)
        setOnClickListener {
            playTinyPulse(this)
            showUpdateLogDialog()
        }
    }
}

private fun MainUiHost.showUpdateLogDialog() {
    var dialog: Dialog? = null
    var openFullLog = false
    val bodyViews = mutableListOf<View>()
    val prepareBodyView: (View) -> View = { view ->
        if (ValueAnimator.areAnimatorsEnabled()) {
            view.alpha = 0f
            view.translationY = dp(6).toFloat()
        }
        bodyViews += view
        view
    }
    dialog = showAirDialog(
        title = getString(R.string.ui_whats_new),
        message = null,
        positiveText = getString(R.string.ui_ok),
        coordinatedDim = true,
        afterUserDismiss = { if (openFullLog) showFullUpdateLogDialog() },
        body = {
            addView(prepareBodyView(changelogTextView(loadCurrentChangelogText())))
            addView(prepareBodyView(changelogDialogButton(getString(R.string.ui_view_full_changelog)) {
                openFullLog = true
                dialog?.dismiss()
            }))
        }
    )
    dialog.window?.decorView?.apply {
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                bodyViews.forEach {
                    it.animate().cancel()
                    it.animate().setStartDelay(0L)
                    it.alpha = 1f
                    it.translationY = 0f
                }
            }
        })
        post {
            if (!dialog.isShowing || !ValueAnimator.areAnimatorsEnabled()) {
                bodyViews.forEach { it.alpha = 1f; it.translationY = 0f }
                return@post
            }
            bodyViews.forEachIndexed { index, view ->
                view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(65L + index * 45L)
                    .setDuration(150L)
                    .setInterpolator(DecelerateInterpolator(1.2f))
                    .withEndAction { view.animate().setStartDelay(0L) }
                    .start()
            }
        }
    }
}

internal fun MainUiHost.showFullUpdateLogDialog() {
    if (isAuxiliaryDialogShowing("changelog")) return
    rememberAuxiliaryDialog("changelog")
    auxiliaryDialogs["changelog"] = showAirDialog(
        title = getString(R.string.ui_changelog),
        scrollStateKey = "aux.changelog.scroll",
        onUserDismiss = { forgetAuxiliaryDialog("changelog") },
        message = null,
        positiveText = getString(R.string.ui_ok),
        coordinatedDim = true,
        body = {
            addView(changelogTextView(loadChangelogText()))
        }
    )
}

private fun MainUiHost.changelogTextView(text: String): View {
    return TextView(this).apply {
        this.text = text
        textSize = AirUiTokens.TextSize.Body
        setTextColor(colorTextMuted)
        setLineSpacing(dp(AirUiTokens.Space.Sm).toFloat(), 1f)
        setPadding(0, dp(AirUiTokens.Space.Xxl + AirUiTokens.Space.Xxs), 0, dp(AirUiTokens.Space.Sm))
    }
}

private fun MainUiHost.changelogDialogButton(
    text: String,
    onClick: () -> Unit
): View {
    return TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = AirUiTokens.TextSize.Button
        typeface = Typeface.DEFAULT_BOLD
        minimumHeight = dp(AirUiTokens.Layout.IconTouchSize)
        setPadding(dp(AboutTokens.CHANGE_LOG_HORIZONTAL_PADDING_DP), dp(AboutTokens.CHANGE_LOG_VERTICAL_PADDING_DP), dp(AboutTokens.CHANGE_LOG_HORIZONTAL_PADDING_DP), dp(AboutTokens.CHANGE_LOG_VERTICAL_PADDING_DP))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, dp(AirUiTokens.Space.Xl), 0, 0)
        }
        styleAirTonalButton(this)
        isClickable = true
        isFocusable = true
        enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale + 0.01f)
        setOnClickListener {
            playTinyPulse(this)
            onClick()
        }
    }
}

private fun MainUiHost.loadCurrentChangelogText(): String {
    return loadAssetText(
        assetName = "changelog_current.txt",
        fallback = loadChangelogText()
    )
}

private fun MainUiHost.loadChangelogText(): String {
    return loadAssetText(
        assetName = "changelog.txt",
        fallback = getString(R.string.ui_no_changelog_yet)
    )
}

private fun MainUiHost.loadAssetText(assetName: String, fallback: String): String {
    return runCatching {
        assets.open(assetName).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrDefault(fallback)
        .trim()
        .ifBlank { fallback }
}
