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
import androidx.core.view.isVisible
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.ButtonStyle
import com.andsi.airlyrics.core.model.ThemeAccent
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.AirTonalButtonTone
import com.andsi.airlyrics.ui.components.adaptiveHeader
import com.andsi.airlyrics.ui.components.airIconView
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.styleAirTonalButton
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.AirLyricsTheme
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorCard
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import com.andsi.airlyrics.ui.theme.iconColorOnAccent

/** Settings title and the app-wide appearance controls shown beside it. */
internal fun createSettingsAppearanceHeader(host: MainUiHost): View {
    return SettingsAppearanceHeader(host)
}

internal enum class AppearanceMenu { NONE, ACCENT, BUTTON_STYLE }

internal class AppearanceMenuState {
    var openMenu: AppearanceMenu = AppearanceMenu.NONE
        private set

    fun toggle(menu: AppearanceMenu): AppearanceMenu {
        require(menu != AppearanceMenu.NONE)
        openMenu = if (openMenu == menu) AppearanceMenu.NONE else menu
        return openMenu
    }
}

/** Both panes use the same progress so an interrupted fade keeps its current opacity. */
internal fun menuCrossfadeAlphas(
    accentStart: Float,
    buttonStyleStart: Float,
    target: AppearanceMenu,
    progress: Float
): Pair<Float, Float> {
    require(target != AppearanceMenu.NONE)
    val fraction = progress.coerceIn(0f, 1f)
    val accentTarget = if (target == AppearanceMenu.ACCENT) 1f else 0f
    val buttonStyleTarget = if (target == AppearanceMenu.BUTTON_STYLE) 1f else 0f
    return (accentStart + (accentTarget - accentStart) * fraction) to
        (buttonStyleStart + (buttonStyleTarget - buttonStyleStart) * fraction)
}

@SuppressLint("ViewConstructor") // Programmatic-only view; MainUiHost is its required UI boundary.
private class SettingsAppearanceHeader(
    private val host: MainUiHost
) : LinearLayout(host) {
    private val menuState = AppearanceMenuState()
    private val accentContent: LinearLayout = createAccentContent()
    private val buttonStyleContent: LinearLayout = createButtonStyleContent()
    private val contentTouchBlocker = View(host).apply {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
        layoutParams = FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
    }
    private val pickerContent = FrameLayout(host).apply {
        layoutParams = FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        )
        addView(accentContent)
        addView(buttonStyleContent)
        buttonStyleContent.visibility = INVISIBLE
        buttonStyleContent.alpha = 0f
        addView(contentTouchBlocker)
    }
    private val pickerContainer: FrameLayout = createPickerContainer()
    private val accentButton: FrameLayout = createAccentButton()
    private val buttonStyleButton: FrameLayout = createButtonStyleButton()
    private var pickerAnimator: AnimatorSet? = null
    private var contentAnimator: ValueAnimator? = null

    init {
        orientation = VERTICAL
        setPadding(0, 0, 0, host.dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Lg))

        val heading = TextView(host).apply {
            setText(R.string.ui_settings)
            textSize = AirUiTokens.TextSize.PageTitle
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(host.colorTextStrong)
        }
        val controls = LinearLayout(host).apply {
            orientation = HORIZONTAL
            addView(buttonStyleButton)
            addView(accentButton)
            addView(host.themeToggleButton())
        }
        addView(host.adaptiveHeader(heading, controls))

        addView(pickerContainer)
    }

    override fun onDetachedFromWindow() {
        cancelPickerAnimation()
        cancelContentAnimation()
        val openMenu = menuState.openMenu
        if (openMenu != AppearanceMenu.NONE) {
            showContent(openMenu)
        } else {
            setContentInteractive(AppearanceMenu.NONE)
        }
        pickerContainer.layoutParams = pickerContainer.layoutParams.apply {
            height = LayoutParams.WRAP_CONTENT
        }
        pickerContainer.visibility = if (openMenu == AppearanceMenu.NONE) GONE else VISIBLE
        pickerContainer.alpha = if (openMenu == AppearanceMenu.NONE) 0f else 1f
        pickerContent.alpha = if (openMenu == AppearanceMenu.NONE) 0f else 1f
        pickerContainer.translationY = 0f
        super.onDetachedFromWindow()
    }

    private fun createAccentButton(): FrameLayout {
        return FrameLayout(host).apply {
            contentDescription = host.getString(R.string.ui_choose_accent_color)
            layoutParams = LayoutParams(
                host.dp(AirUiTokens.Layout.ThemeToggleSize),
                host.dp(AirUiTokens.Layout.ThemeToggleSize)
            ).apply { setMargins(host.dp(AirUiTokens.Space.Xxl), 0, 0, 0) }
            elevation = host.dp(AirUiTokens.Space.Xxs).toFloat()
            isClickable = true
            isFocusable = true
            enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
            background = host.appearanceControlBackground()

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
                toggleMenu(AppearanceMenu.ACCENT)
            }
        }
    }

    private fun createButtonStyleButton(): FrameLayout {
        return FrameLayout(host).apply {
            contentDescription = host.getString(
                R.string.ui_choose_button_style,
                host.getString(host.buttonStyle().labelRes())
            )
            layoutParams = LayoutParams(
                host.dp(AirUiTokens.Layout.ThemeToggleSize),
                host.dp(AirUiTokens.Layout.ThemeToggleSize)
            )
            elevation = host.dp(AirUiTokens.Space.Xxs).toFloat()
            isClickable = true
            isFocusable = true
            enableSoftPressFeedback(AirUiTokens.Motion.StrongPressScale)
            background = host.appearanceControlBackground()

            addView(View(host).apply {
                host.styleAirTonalButton(
                    this,
                    AirTonalButtonTone.PRIMARY,
                    radiusDp = AirUiTokens.Space.Md
                )
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = FrameLayout.LayoutParams(
                    host.dp(AirUiTokens.Layout.ThemeButtonStyleGlyphWidth),
                    host.dp(AirUiTokens.Layout.ThemeButtonStyleGlyphHeight),
                    Gravity.CENTER
                )
            })
            setOnClickListener { toggleMenu(AppearanceMenu.BUTTON_STYLE) }
        }
    }

    private fun createPickerContainer(): FrameLayout {
        return FrameLayout(host).apply {
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
            clipToOutline = true
            addView(pickerContent)
        }
    }

    private fun createPickerContent(titleRes: Int, optionRow: View): LinearLayout {
        return LinearLayout(host).apply {
            orientation = VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
            addView(TextView(host).apply {
                setText(titleRes)
                textSize = AirUiTokens.TextSize.BodySmall
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(host.colorTextMuted)
            })
            optionRow.setPadding(0, host.dp(AirUiTokens.Space.Xl), 0, 0)
            addView(optionRow)
        }
    }

    private fun createAccentContent(): LinearLayout {
        val options = LinearLayout(host).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            ThemeAccent.entries.forEach { addView(createAccentSwatch(it)) }
        }
        return createPickerContent(R.string.ui_accent_color, options)
    }

    private fun createButtonStyleContent(): LinearLayout {
        val options = LinearLayout(host).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            ButtonStyle.entries.forEach { style ->
                addView(createButtonStyleOption(style))
            }
        }
        return createPickerContent(R.string.ui_button_style, options)
    }

    private fun createButtonStyleOption(style: ButtonStyle): TextView {
        val selected = style == host.buttonStyle()
        val label = host.getString(style.labelRes())
        return TextView(host).apply {
            text = label
            textSize = AirUiTokens.TextSize.Body
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isSelected = selected
            contentDescription = if (selected) {
                host.getString(R.string.ui_button_style_selected, label)
            } else {
                label
            }
            isClickable = true
            isFocusable = true
            layoutParams = LayoutParams(
                0,
                host.dp(AirUiTokens.Layout.ThemeAccentTouchSize),
                1f
            ).apply {
                if (style == ButtonStyle.TRANSPARENT) marginStart = host.dp(AirUiTokens.Space.Lg)
            }
            host.styleAirTonalButton(
                this,
                AirTonalButtonTone.PRIMARY,
                buttonStyle = style,
                selectionOutline = selected
            )
            enableSoftPressFeedback(AirUiTokens.Motion.OptionPressScale)
            setOnClickListener {
                if (selected) {
                    toggleMenu(AppearanceMenu.BUTTON_STYLE)
                } else {
                    host.uiActions.selectButtonStyle(style)
                }
            }
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
                    toggleMenu(AppearanceMenu.ACCENT)
                } else {
                    host.uiActions.selectThemeAccent(accent)
                }
            }
        }
    }

    private fun toggleMenu(menu: AppearanceMenu) {
        val previous = menuState.openMenu
        val current = menuState.toggle(menu)
        accentButton.background = host.appearanceControlBackground(current == AppearanceMenu.ACCENT)
        buttonStyleButton.background = host.appearanceControlBackground(current == AppearanceMenu.BUTTON_STYLE)

        when {
            current == AppearanceMenu.NONE -> {
                cancelContentAnimation()
                setContentInteractive(AppearanceMenu.NONE)
                animatePicker(expanded = false)
            }
            previous == AppearanceMenu.NONE -> {
                if (pickerContainer.isVisible) {
                    switchContent(current)
                } else {
                    cancelContentAnimation()
                    showContent(current)
                }
                animatePicker(expanded = true)
            }
            else -> switchContent(current)
        }
    }

    private fun showContent(menu: AppearanceMenu) {
        require(menu != AppearanceMenu.NONE)
        accentContent.visibility = if (menu == AppearanceMenu.ACCENT) VISIBLE else INVISIBLE
        buttonStyleContent.visibility = if (menu == AppearanceMenu.BUTTON_STYLE) VISIBLE else INVISIBLE
        accentContent.alpha = if (menu == AppearanceMenu.ACCENT) 1f else 0f
        buttonStyleContent.alpha = if (menu == AppearanceMenu.BUTTON_STYLE) 1f else 0f
        setContentInteractive(menu)
    }

    private fun setContentInteractive(menu: AppearanceMenu) {
        val accentActive = menu == AppearanceMenu.ACCENT
        val buttonStyleActive = menu == AppearanceMenu.BUTTON_STYLE
        if (!accentActive) accentContent.findFocus()?.clearFocus()
        if (!buttonStyleActive) buttonStyleContent.findFocus()?.clearFocus()
        accentContent.descendantFocusability = if (accentActive) {
            FOCUS_AFTER_DESCENDANTS
        } else {
            FOCUS_BLOCK_DESCENDANTS
        }
        buttonStyleContent.descendantFocusability = if (buttonStyleActive) {
            FOCUS_AFTER_DESCENDANTS
        } else {
            FOCUS_BLOCK_DESCENDANTS
        }
        accentContent.importantForAccessibility = if (accentActive) {
            IMPORTANT_FOR_ACCESSIBILITY_AUTO
        } else {
            IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        buttonStyleContent.importantForAccessibility = if (buttonStyleActive) {
            IMPORTANT_FOR_ACCESSIBILITY_AUTO
        } else {
            IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        contentTouchBlocker.visibility = if (menu == AppearanceMenu.NONE) VISIBLE else GONE
    }

    private fun switchContent(menu: AppearanceMenu) {
        cancelContentAnimation()
        val accentStart = if (accentContent.isVisible) accentContent.alpha else 0f
        val buttonStyleStart = if (buttonStyleContent.isVisible) buttonStyleContent.alpha else 0f
        if (!isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            showContent(menu)
            return
        }
        val accentTarget = if (menu == AppearanceMenu.ACCENT) 1f else 0f
        val buttonStyleTarget = if (menu == AppearanceMenu.BUTTON_STYLE) 1f else 0f
        if (accentStart == accentTarget && buttonStyleStart == buttonStyleTarget) {
            showContent(menu)
            return
        }
        accentContent.visibility = VISIBLE
        buttonStyleContent.visibility = VISIBLE
        setContentInteractive(AppearanceMenu.NONE)
        contentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = AirUiTokens.Layout.FastFadeMs * 2
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { animation ->
                val progress = animation.animatedValue as Float
                val (accentAlpha, buttonStyleAlpha) = menuCrossfadeAlphas(
                    accentStart, buttonStyleStart, menu, progress
                )
                accentContent.alpha = accentAlpha
                buttonStyleContent.alpha = buttonStyleAlpha
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    showContent(menu)
                    contentAnimator = null
                }
            })
            start()
        }
    }

    private fun animatePicker(expanded: Boolean) {
        cancelPickerAnimation()
        val wasHidden = pickerContainer.isGone
        val startHeight = if (wasHidden) 0 else {
            pickerContainer.layoutParams.height.takeIf { it >= 0 } ?: pickerContainer.height
        }
        val fullHeight = measurePickerHeight()
        val targetHeight = if (expanded) fullHeight else 0
        if (expanded) {
            pickerContainer.visibility = VISIBLE
            if (wasHidden) {
                pickerContainer.alpha = 0f
                pickerContainer.translationY =
                    -host.dp(AirUiTokens.Layout.ThemePickerSlideDistance).toFloat()
            }
        }
        pickerContent.alpha = heightRevealContentAlpha(
            startHeight, fullHeight - pickerContainer.paddingBottom, fullHeight
        )
        if (!isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            pickerContainer.layoutParams = pickerContainer.layoutParams.apply {
                height = LayoutParams.WRAP_CONTENT
            }
            pickerContainer.visibility = if (expanded) VISIBLE else GONE
            pickerContainer.alpha = if (expanded) 1f else 0f
            pickerContent.alpha = if (expanded) 1f else 0f
            pickerContainer.translationY = 0f
            return
        }
        val animatedParams = pickerContainer.layoutParams.apply { height = startHeight }
        pickerContainer.layoutParams = animatedParams
        val heightAnimator = ValueAnimator.ofInt(startHeight, targetHeight).apply {
            addUpdateListener { animation ->
                animatedParams.height = animation.animatedValue as Int
                pickerContent.alpha = heightRevealContentAlpha(
                    animatedParams.height, fullHeight - pickerContainer.paddingBottom, fullHeight
                )
                pickerContainer.requestLayout()
            }
        }
        val alphaAnimator = ObjectAnimator.ofFloat(
            pickerContainer, ALPHA, pickerContainer.alpha, if (expanded) 1f else 0f
        )
        val translationAnimator = ObjectAnimator.ofFloat(
            pickerContainer,
            TRANSLATION_Y,
            pickerContainer.translationY,
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
                    pickerContainer.layoutParams = animatedParams.apply {
                        height = LayoutParams.WRAP_CONTENT
                    }
                    pickerContent.alpha = if (expanded) 1f else 0f
                    if (!expanded) {
                        pickerContainer.visibility = GONE
                        pickerContainer.translationY = 0f
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

    private fun cancelContentAnimation() {
        contentAnimator?.removeAllListeners()
        contentAnimator?.removeAllUpdateListeners()
        contentAnimator?.cancel()
        contentAnimator = null
    }

    private fun measurePickerHeight(): Int {
        val availableWidth = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        pickerContainer.measure(
            MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        return pickerContainer.measuredHeight
    }
}

internal fun MainUiHost.appearanceControlBackground(expanded: Boolean = false): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(colorSurfaceLight)
        setStroke(
            dp(if (expanded) AirUiTokens.Stroke.Selected else AirUiTokens.Stroke.Hairline),
            if (expanded) colorAccent else colorStroke
        )
    }

private fun ButtonStyle.labelRes(): Int = when (this) {
    ButtonStyle.SOLID -> R.string.ui_button_style_solid
    ButtonStyle.TRANSPARENT -> R.string.ui_button_style_transparent
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
