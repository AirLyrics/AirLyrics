package com.andsi.airlyrics.app.host

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.platform.AppNavigator
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.airIconView
import com.andsi.airlyrics.ui.components.bigText
import com.andsi.airlyrics.ui.components.card
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.normalText
import com.andsi.airlyrics.ui.components.smallHint
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import com.andsi.airlyrics.ui.navigation.parentPage
import com.andsi.airlyrics.ui.pages.settings.createSettingsAppearanceHeader
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorIconOnAccent
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong

internal fun MainUiHost.settingsHomeHeaderImpl(): View {
    return createSettingsAppearanceHeader(this)
}

internal fun MainUiHost.settingsBackHeaderImpl(
    title: String,
    subtitle: String = "",
    titleAction: View? = null
): View {
    val activity = this
    val parentPage = settingsSubPage.parentPage()
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Lg))
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, 0, 0, dp(AirUiTokens.Space.Xxl))
            enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
            setOnClickListener {
                parentPage?.let(uiActions.openSettingsSubPage)
            }
            addView(airIconView(R.drawable.ic_air_arrow_back, colorAccent).apply {
                layoutParams = LinearLayout.LayoutParams(
                    dp(AirUiTokens.Layout.IconSize),
                    dp(AirUiTokens.Layout.IconSize)
                )
            })
            addView(TextView(activity).apply {
                setText(if (parentPage == SettingsSubPage.LYRICS) {
                    R.string.ui_lyrics
                } else {
                    R.string.ui_settings_back_label
                })
                textSize = AirUiTokens.TextSize.Body
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorAccent)
                setPadding(dp(AirUiTokens.Space.Lg), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        })
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = title
                textSize = AirUiTokens.TextSize.PageTitle
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextStrong)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            })
            titleAction?.let { action ->
                addView(action)
            }
        })
        if (subtitle.isNotBlank()) {
            addView(TextView(activity).apply {
                text = subtitle
                textSize = AirUiTokens.TextSize.Body
                setTextColor(colorTextMuted)
                setPadding(0, dp(AirUiTokens.Space.Sm), 0, 0)
            })
        }
    }
}

internal fun MainUiHost.themeToggleButtonImpl(): View {
    val darkTheme = isDarkTheme()
    val contentDescription = getString(
        if (darkTheme) R.string.ui_switch_to_light_mode else R.string.ui_switch_to_dark_mode
    )
    return airIconView(
        iconRes = if (darkTheme) R.drawable.ic_air_light_mode else R.drawable.ic_air_dark_mode,
        tint = colorAccent,
        contentDescription = contentDescription
    ).apply {
        layoutParams = LinearLayout.LayoutParams(dp(AirUiTokens.Layout.ThemeToggleSize), dp(AirUiTokens.Layout.ThemeToggleSize)).apply {
            setMargins(dp(AirUiTokens.Space.Xxl), 0, 0, 0)
        }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorSurfaceLight)
            setStroke(dp(AirUiTokens.Stroke.Hairline), colorStroke)
        }
        elevation = dp(AirUiTokens.Space.Xxs).toFloat()
        enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
        setOnClickListener {
            uiActions.toggleThemeMode()
        }
    }
}

internal fun MainUiHost.settingsCategoryCardImpl(
    title: String,
    subtitle: String,
    status: String,
    iconRes: Int,
    onClick: () -> Unit
): View {
    val activity = this
    return card(activity) {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        enableSoftPressFeedback(AirUiTokens.Motion.FloatingCardPressScale)
        setOnClickListener { onClick() }

        addView(FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(AirUiTokens.Layout.SettingsIconBubbleSize), dp(AirUiTokens.Layout.SettingsIconBubbleSize)).apply {
                setMargins(0, 0, dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Lg), 0)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorAccent)
            }
            addView(airIconView(iconRes, colorIconOnAccent).apply {
                layoutParams = FrameLayout.LayoutParams(
                    dp(AirUiTokens.Layout.IconSize),
                    dp(AirUiTokens.Layout.IconSize),
                    Gravity.CENTER
                )
            })
        })

        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(bigText(activity, title))
            addView(normalText(activity, subtitle))
            addView(smallHint(activity, status))
        })

        addView(airIconView(R.drawable.ic_air_chevron_right, colorAccent).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(AirUiTokens.Layout.IconSize),
                dp(AirUiTokens.Layout.IconSize)
            )
        })
    }
}

internal fun MainUiHost.changelogItemImpl(title: String, body: String): View {
    val activity = this
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(AirUiTokens.Space.Xxl), 0, dp(AirUiTokens.Space.Xxs))
        addView(TextView(activity).apply {
            text = title
            textSize = AirUiTokens.TextSize.Button
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextStrong)
        })
        addView(TextView(activity).apply {
            text = body
            textSize = AirUiTokens.TextSize.BodySmall
            setTextColor(colorTextMuted)
            setPadding(0, dp(AirUiTokens.Space.Xs), 0, 0)
        })
    }
}

internal fun MainUiHost.permissionSummaryImpl(): String {
    return getString(
        if (overlayPermissionGranted && hasNotificationListenerAccess()) {
            R.string.ui_basic_features_ready
        } else {
            R.string.ui_setup_required
        }
    )
}

internal fun MainUiHost.getAppVersionNameImpl(): String {
    return try {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        packageInfo.versionName ?: "1.0"
    } catch (_: Exception) {
        "1.0"
    }
}

internal fun MainUiHost.openUrlImpl(url: String) {
    AppNavigator.openUrl(this, url)
}


internal fun MainUiHost.refreshAfterLanguageChangedImpl() {
    val oldContainer = contentContainer
    oldContainer?.animate()
        ?.alpha(0f)
        ?.setDuration(AirUiTokens.Layout.FastFadeMs)
        ?.withEndAction {
            rebuildMainView()
            contentContainer?.alpha = 0f
            contentContainer?.animate()
                ?.alpha(1f)
                ?.setDuration(AirUiTokens.Layout.RestoreFadeMs)
                ?.setInterpolator(DecelerateInterpolator())
                ?.start()
        }
        ?.start()
        ?: run {
            rebuildMainView()
        }
    uiActions.reloadFloatingLyrics()
}
