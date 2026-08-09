package com.andsi.airlyrics.ui.pages.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isGone
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.ThemeAccent
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.airIconView
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.AirLyricsTheme
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorCard
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import com.andsi.airlyrics.ui.theme.iconColorOnAccent

/** Settings title and the two app-wide appearance controls shown beside it. */
internal fun createSettingsAppearanceHeader(host: MainUiHost): View {
    return SettingsAppearanceHeader(host)
}

@SuppressLint("ViewConstructor") // Programmatic-only view; MainUiHost is its required UI boundary.
private class SettingsAppearanceHeader(
    private val host: MainUiHost
) : LinearLayout(host) {
    private val accentPicker: LinearLayout = createAccentPicker()
    private val accentButton: FrameLayout = createAccentButton()
    private var pickerExpanded = false
    private var pickerAnimator: AnimatorSet? = null

    init {
        orientation = VERTICAL
        setPadding(0, 0, 0, host.dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Lg))

        addView(LinearLayout(host).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(TextView(host).apply {
                text = host.getString(R.string.ui_settings)
                textSize = AirUiTokens.TextSize.PageTitle
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(host.colorTextStrong)
                layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(accentButton)
            addView(host.themeToggleButton())
        })

        addView(accentPicker)
    }

    override fun onDetachedFromWindow() {
        cancelPickerAnimation()
        super.onDetachedFromWindow()
    }

    private fun createAccentButton(): FrameLayout {
        return FrameLayout(host).apply {
            contentDescription = host.getString(R.string.ui_choose_accent_color)
            layoutParams = LayoutParams(
                host.dp(AirUiTokens.Layout.ThemeToggleSize),
                host.dp(AirUiTokens.Layout.ThemeToggleSize)
            )
            elevation = host.dp(AirUiTokens.Space.Xxs).toFloat()
            isClickable = true
            isFocusable = true
            enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
            setAccentButtonBackground(this, expanded = false)

            addView(View(host).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(host.colorAccent)
                }
                layoutParams = FrameLayout.LayoutParams(
                    host.dp(AirUiTokens.Layout.ThemeAccentDotSize),
                    host.dp(AirUiTokens.Layout.ThemeAccentDotSize),
                    Gravity.CENTER
                )
            })

            setOnClickListener {
                setPickerExpanded(!pickerExpanded)
            }
        }
    }

    private fun createAccentPicker(): LinearLayout {
        return LinearLayout(host).apply {
            orientation = VERTICAL
            visibility = GONE
            alpha = 0f
            setPadding(
                host.dp(AirUiTokens.Space.CardH),
                host.dp(AirUiTokens.Space.Xxl),
                host.dp(AirUiTokens.Space.CardH),
                host.dp(AirUiTokens.Space.Xxl)
            )
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, host.dp(AirUiTokens.Space.Xxl), 0, 0)
            }
            background = GradientDrawable().apply {
                cornerRadius = host.dp(AirUiTokens.Radius.Md).toFloat()
                setColor(host.colorCard)
                setStroke(host.dp(AirUiTokens.Stroke.Hairline), host.colorStroke)
            }

            addView(TextView(host).apply {
                text = host.getString(R.string.ui_accent_color)
                textSize = AirUiTokens.TextSize.BodySmall
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(host.colorTextMuted)
            })

            addView(LinearLayout(host).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, host.dp(AirUiTokens.Space.Xl), 0, 0)
                ThemeAccent.entries.forEach { accent ->
                    addView(createAccentSwatch(accent))
                }
            })
        }
    }

    private fun createAccentSwatch(accent: ThemeAccent): View {
        val selected = accent == host.themeAccent()
        val palette = AirLyricsTheme.palette(host.isDarkTheme(), accent)
        val colorName = accent.displayName(host)

        return FrameLayout(host).apply {
            layoutParams = LayoutParams(
                0,
                host.dp(AirUiTokens.Layout.ThemeAccentTouchSize),
                1f
            )
            contentDescription = host.getString(
                if (selected) R.string.ui_accent_color_selected else R.string.ui_accent_color_option,
                colorName
            )
            isClickable = true
            isFocusable = true
            enableSoftPressFeedback(AirUiTokens.Motion.OptionPressScale)

            addView(FrameLayout(host).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(palette.accent)
                    setStroke(
                        host.dp(if (selected) AirUiTokens.Stroke.Selected else AirUiTokens.Stroke.Hairline),
                        if (selected) host.colorTextStrong else host.colorStroke
                    )
                }
                layoutParams = FrameLayout.LayoutParams(
                    host.dp(AirUiTokens.Layout.ThemeAccentSwatchSize),
                    host.dp(AirUiTokens.Layout.ThemeAccentSwatchSize),
                    Gravity.CENTER
                )
                if (selected) {
                    addView(host.airIconView(
                        R.drawable.ic_air_check,
                        iconColorOnAccent(palette.onAccent)
                    ).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            host.dp(AirUiTokens.Layout.IconSize),
                            host.dp(AirUiTokens.Layout.IconSize),
                            Gravity.CENTER
                        )
                    })
                }
            })

            setOnClickListener {
                if (selected) {
                    setPickerExpanded(false)
                } else {
                    host.uiActions.selectThemeAccent(accent)
                }
            }
        }
    }

    private fun setPickerExpanded(expanded: Boolean) {
        if (pickerExpanded == expanded) return
        pickerExpanded = expanded
        setAccentButtonBackground(accentButton, expanded)

        cancelPickerAnimation()

        val wasHidden = accentPicker.isGone
        val startHeight = if (wasHidden) 0 else accentPicker.height
        val targetHeight = if (expanded) measurePickerHeight() else 0
        if (expanded) {
            accentPicker.visibility = VISIBLE
            if (wasHidden) {
                accentPicker.alpha = 0f
                accentPicker.translationY =
                    -host.dp(AirUiTokens.Layout.ThemePickerSlideDistance).toFloat()
            }
        }
        val animatedLayoutParams = accentPicker.layoutParams
        animatedLayoutParams.height = startHeight
        accentPicker.layoutParams = animatedLayoutParams

        val heightAnimator = ValueAnimator.ofInt(startHeight, targetHeight).apply {
            addUpdateListener { animation ->
                animatedLayoutParams.height = animation.animatedValue as Int
                accentPicker.requestLayout()
            }
        }
        val alphaAnimator = ObjectAnimator.ofFloat(
            accentPicker,
            ALPHA,
            accentPicker.alpha,
            if (expanded) 1f else 0f
        )
        val translationAnimator = ObjectAnimator.ofFloat(
            accentPicker,
            TRANSLATION_Y,
            accentPicker.translationY,
            if (expanded) 0f else -host.dp(AirUiTokens.Layout.ThemePickerSlideDistance).toFloat()
        )

        pickerAnimator = AnimatorSet().apply {
            duration = AirUiTokens.Motion.ThemePickerExpandMs
            interpolator = if (expanded) {
                PathInterpolator(0.2f, 0f, 0f, 1f)
            } else {
                PathInterpolator(0.4f, 0f, 1f, 1f)
            }
            playTogether(heightAnimator, alphaAnimator, translationAnimator)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animatedLayoutParams.height = LayoutParams.WRAP_CONTENT
                    accentPicker.layoutParams = animatedLayoutParams
                    if (!expanded) {
                        accentPicker.visibility = GONE
                        accentPicker.translationY = 0f
                    }
                    pickerAnimator = null
                }
            })
            start()
        }
    }

    private fun cancelPickerAnimation() {
        pickerAnimator?.removeAllListeners()
        pickerAnimator?.cancel()
        pickerAnimator = null
    }

    private fun measurePickerHeight(): Int {
        accentPicker.layoutParams = accentPicker.layoutParams.apply {
            height = LayoutParams.WRAP_CONTENT
        }
        val availableWidth = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        accentPicker.measure(
            MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        return accentPicker.measuredHeight
    }

    private fun setAccentButtonBackground(button: View, expanded: Boolean) {
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(host.colorSurfaceLight)
            setStroke(
                host.dp(if (expanded) AirUiTokens.Stroke.Selected else AirUiTokens.Stroke.Hairline),
                if (expanded) host.colorAccent else host.colorStroke
            )
        }
    }
}

private fun ThemeAccent.displayName(host: MainUiHost): String {
    return host.getString(
        when (this) {
            ThemeAccent.PINK -> R.string.ui_pink
            ThemeAccent.ORANGE -> R.string.ui_orange
            ThemeAccent.GREEN -> R.string.ui_green
            ThemeAccent.BLUE -> R.string.ui_blue
            ThemeAccent.PURPLE -> R.string.ui_purple
        }
    )
}
