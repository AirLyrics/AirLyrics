package com.andsi.airlyrics.ui.pages.settings

import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.components.bigText
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.playTinyPulse
import com.andsi.airlyrics.ui.components.scroll
import com.andsi.airlyrics.ui.components.showAirDialog
import com.andsi.airlyrics.ui.components.statusPill
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorAccentSoft
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.design.tokens.AirUiTokens



internal fun createAboutSettingsPage(activity: MainUiHost): View = with(activity) {
    val container = com.andsi.airlyrics.ui.components.pageContainer(activity)
    container.addView(settingsBackHeader(getString(R.string.ui_about)))
    container.addView(aboutLogoHeader())
    container.addView(changeLogButton())
    return scroll(activity, container)
}

private fun MainUiHost.aboutLogoHeader(): View {
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
            setColorFilter(colorAccent)
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = FrameLayout.LayoutParams(dp(AboutTokens.GITHUB_ICON_SIZE_DP), dp(AboutTokens.GITHUB_ICON_SIZE_DP), Gravity.CENTER)
            contentDescription = "GitHub"
        })
    }
}

private fun MainUiHost.changeLogButton(): View {
    val activity = this
    return TextView(activity).apply {
        text = getString(R.string.ui_changelog)
        gravity = Gravity.CENTER
        textSize = AirUiTokens.TextSize.Button
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(colorAccent)
        setPadding(dp(AboutTokens.CHANGE_LOG_HORIZONTAL_PADDING_DP), dp(AboutTokens.CHANGE_LOG_VERTICAL_PADDING_DP), dp(AboutTokens.CHANGE_LOG_HORIZONTAL_PADDING_DP), dp(AboutTokens.CHANGE_LOG_VERTICAL_PADDING_DP))
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

private fun MainUiHost.showUpdateLogDialog() {
    var dialog: Dialog? = null
    dialog = showAirDialog(
        title = getString(R.string.ui_whats_new),
        message = null,
        positiveText = getString(R.string.ui_ok),
        body = {
            addView(changelogTextView(loadCurrentChangelogText()))
            addView(changelogDialogButton(getString(R.string.ui_view_full_changelog)) {
                dialog?.dismiss()
                showFullUpdateLogDialog()
            })
        }
    )
}

private fun MainUiHost.showFullUpdateLogDialog() {
    showAirDialog(
        title = getString(R.string.ui_changelog),
        message = null,
        positiveText = getString(R.string.ui_ok),
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
        setTextColor(colorAccent)
        setPadding(dp(AboutTokens.CHANGE_LOG_HORIZONTAL_PADDING_DP), dp(AboutTokens.CHANGE_LOG_VERTICAL_PADDING_DP), dp(AboutTokens.CHANGE_LOG_HORIZONTAL_PADDING_DP), dp(AboutTokens.CHANGE_LOG_VERTICAL_PADDING_DP))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, dp(AirUiTokens.Space.Xl), 0, 0)
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
