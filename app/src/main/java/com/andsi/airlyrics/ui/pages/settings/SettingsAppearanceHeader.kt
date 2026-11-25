package com.andsi.airlyrics.ui.pages.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.ceil
import kotlin.math.roundToInt
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withTranslation
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
import com.andsi.airlyrics.ui.components.applyAirTonalButtonStyle
import com.andsi.airlyrics.ui.theme.bindAppearance
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.AirLyricsTheme
import com.andsi.airlyrics.ui.theme.colorAccent
import com.andsi.airlyrics.ui.theme.colorCard
import com.andsi.airlyrics.ui.theme.colorStroke
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import com.andsi.airlyrics.ui.theme.iconColorOnAccent

internal fun createSettingsAppearanceHeader(host: MainUiHost): View {
    return SettingsAppearanceHeader(host)
}

internal enum class AppearanceMenu { NONE, ACCENT, BUTTON_STYLE }

private const val APPEARANCE_MENU_KEY = "settings.home.appearanceMenu"

internal class AppearanceMenuState {
    var openMenu: AppearanceMenu = AppearanceMenu.NONE
        private set

    fun toggle(menu: AppearanceMenu): AppearanceMenu {
        require(menu != AppearanceMenu.NONE)
        openMenu = if (openMenu == menu) AppearanceMenu.NONE else menu
        return openMenu
    }
}

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

@SuppressLint("ViewConstructor")
private class SettingsAppearanceHeader(
    private val host: MainUiHost
) : LinearLayout(host) {
    private val menuState = AppearanceMenuState().apply {
        val restored = host.interactions.read(APPEARANCE_MENU_KEY)?.getString("menu")
        AppearanceMenu.entries.firstOrNull { it.name == restored && it != AppearanceMenu.NONE }?.let(::toggle)
    }
    private var pickerProgress = if (menuState.openMenu == AppearanceMenu.NONE) 0f else 1f
    private var surfaceMenu = menuState.openMenu.takeIf { it != AppearanceMenu.NONE } ?: AppearanceMenu.ACCENT
    private var fullPickerHeight = 0
    private var pickerTarget = pickerProgress
    private val surface = AppearancePanelSurface()
    private val anchorBounds = Rect()
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
    private var pickerAnimator: ValueAnimator? = null
    private var contentAnimator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        orientation = VERTICAL
        setPadding(0, 0, 0, host.dp(AirUiTokens.Space.Xl + AirUiTokens.Space.Lg))

        val heading = TextView(host).apply {
            setText(R.string.ui_settings)
            textSize = AirUiTokens.TextSize.PageTitle
            typeface = Typeface.DEFAULT_BOLD
            host.bindAppearance(this) { it.setTextColor(host.colorTextStrong) }
        }
        val controls = LinearLayout(host).apply {
            orientation = HORIZONTAL
            addView(buttonStyleButton)
            addView(accentButton)
            addView(host.themeToggleButton())
        }
        addView(host.adaptiveHeader(heading, controls))

        addView(pickerContainer)
        if (menuState.openMenu != AppearanceMenu.NONE) showContent(menuState.openMenu)
        applyProgress(pickerProgress)
        host.bindAppearance(this) { header ->
            header.updateControls()
            header.invalidate()
        }
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
        pickerTarget = if (openMenu == AppearanceMenu.NONE) 0f else 1f
        applyProgress(pickerTarget)
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
        return object : FrameLayout(host) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                fullPickerHeight = measuredHeight
                setMeasuredDimension(measuredWidth, ceil(fullPickerHeight * AppearancePanelMotion.travel(pickerProgress)).toInt())
            }

            override fun dispatchDraw(canvas: Canvas) {
                val g = panelGeometry()
                val offset = top.toFloat()
                canvas.withTranslation(y = -offset) {
                    surface.clipContent(this, g, pickerProgress)
                    translate(0f, offset)
                    super.dispatchDraw(this)
                }
            }
        }.apply {
            id = R.id.appearance_picker_panel
            visibility = if (pickerProgress == 0f) GONE else VISIBLE
            setPadding(
                host.dp(AirUiTokens.Space.CardH),
                host.dp(AirUiTokens.Space.Xxl * 2),
                host.dp(AirUiTokens.Space.CardH),
                host.dp(AirUiTokens.Space.Xxl)
            )
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
            clipChildren = false
            clipToPadding = false
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
                host.bindAppearance(this) { it.setTextColor(host.colorTextMuted) }
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
            host.bindAppearance(this) { option ->
                val selectedNow = style == host.buttonStyle()
                option.isSelected = selectedNow
                option.contentDescription = if (selectedNow) {
                    host.getString(R.string.ui_button_style_selected, label)
                } else label
                val solid = host.appearance.frame.solidButtonFraction
                host.applyAirTonalButtonStyle(
                    option,
                    AirTonalButtonTone.PRIMARY,
                    buttonStyle = style,
                    selectionOutlineAmount = if (style == ButtonStyle.SOLID) solid else 1f - solid,
                )
            }
            enableSoftPressFeedback(AirUiTokens.Motion.OptionPressScale)
            setOnClickListener {
                if (style == host.buttonStyle()) {
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
                addView(host.airIconView(
                    R.drawable.ic_air_check,
                    iconColorOnAccent(palette.onAccent)
                ).apply {
                    visibility = if (selected) VISIBLE else INVISIBLE
                    layoutParams = FrameLayout.LayoutParams(
                        host.dp(AirUiTokens.Layout.IconSize),
                        host.dp(AirUiTokens.Layout.IconSize),
                        Gravity.CENTER
                    )
                })
            })

            host.bindAppearance(this) { swatch ->
                val selectedNow = accent == host.themeAccent()
                val frame = host.appearance.frame
                val amount = frame.accentSelectionFraction(accent)
                val colors = AirLyricsTheme.palette(false, accent).interpolate(
                    AirLyricsTheme.palette(true, accent), frame.darkFraction
                )
                swatch.isSelected = selectedNow
                swatch.contentDescription = host.getString(
                    if (selectedNow) R.string.ui_accent_color_selected else R.string.ui_accent_color_option,
                    colorName
                )
                val dot = swatch.getChildAt(0) as FrameLayout
                (dot.background as GradientDrawable).apply {
                    setColor(colors.accent)
                    val normalStroke = host.dp(AirUiTokens.Stroke.Hairline)
                    val selectedStroke = host.dp(AirUiTokens.Stroke.Selected)
                    setStroke(
                        (normalStroke + (selectedStroke - normalStroke) * amount).roundToInt(),
                        ColorUtils.blendARGB(host.colorStroke, host.colorTextStrong, amount),
                    )
                }
                (dot.getChildAt(0) as android.widget.ImageView).apply {
                    alpha = amount
                    visibility = if (amount > 0f) VISIBLE else INVISIBLE
                    imageTintList = ColorStateList.valueOf(iconColorOnAccent(colors.onAccent))
                }
            }

            setOnClickListener {
                if (accent == host.themeAccent()) {
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
        if (current == AppearanceMenu.NONE) {
            host.interactions.remove(APPEARANCE_MENU_KEY)
        } else {
            host.interactions.write(APPEARANCE_MENU_KEY) { putString("menu", current.name) }
        }
        if (current != AppearanceMenu.NONE) surfaceMenu = current
        updateControls()

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
            else -> {
                switchContent(current)
                animatePicker(expanded = true)
            }
        }
    }

    private fun showContent(menu: AppearanceMenu) {
        require(menu != AppearanceMenu.NONE)
        accentContent.visibility = if (menu == AppearanceMenu.ACCENT) VISIBLE else INVISIBLE
        buttonStyleContent.visibility = if (menu == AppearanceMenu.BUTTON_STYLE) VISIBLE else INVISIBLE
        accentContent.alpha = if (menu == AppearanceMenu.ACCENT) 1f else 0f
        buttonStyleContent.alpha = if (menu == AppearanceMenu.BUTTON_STYLE) 1f else 0f
        syncInteractiveContent()
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
        for (index in 0 until (accentContent.getChildAt(1) as LinearLayout).childCount) {
            (accentContent.getChildAt(1) as LinearLayout).getChildAt(index).isEnabled = accentActive
        }
        for (index in 0 until (buttonStyleContent.getChildAt(1) as LinearLayout).childCount) {
            (buttonStyleContent.getChildAt(1) as LinearLayout).getChildAt(index).isEnabled = buttonStyleActive
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
            duration = AirUiTokens.Motion.ThemePickerContentSwapMs
            interpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
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
                    contentAnimator = null
                    showContent(menu)
                }
            })
            start()
        }
    }

    private fun animatePicker(expanded: Boolean) {
        val target = if (expanded) 1f else 0f
        if (pickerAnimator != null && pickerTarget == target) return
        cancelPickerAnimation()
        pickerTarget = target
        if (!isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled() ||
            kotlin.math.abs(target - pickerProgress) < 0.001f) {
            applyProgress(target)
            return
        }
        pickerContainer.visibility = VISIBLE
        // Resolve final geometry before the first animated frame, including on a cold open.
        pickerContainer.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val from = pickerProgress
        pickerAnimator = ValueAnimator.ofFloat(from, target).apply {
            duration = AppearancePanelMotion.duration(
                from, target, AirUiTokens.Motion.ThemePickerExpandMs, AirUiTokens.Motion.ThemePickerCollapseMs
            )
            interpolator = LinearInterpolator()
            addUpdateListener { applyProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    pickerAnimator = null
                    applyProgress(target)
                }
            })
            start()
        }
    }

    private fun applyProgress(progress: Float) {
        pickerProgress = progress
        pickerContainer.visibility = if (progress == 0f && pickerTarget == 0f) GONE else VISIBLE
        pickerContent.alpha = AppearancePanelMotion.contentAlpha(progress)
        syncInteractiveContent()
        pickerContainer.requestLayout()
        invalidate()
    }

    private fun syncInteractiveContent() {
        setContentInteractive(
            if (pickerProgress > 0.63f && contentAnimator == null) menuState.openMenu else AppearanceMenu.NONE
        )
    }

    private fun updateControls() {
        val menu = menuState.openMenu
        updateControlBackground(accentButton, menu == AppearanceMenu.ACCENT)
        updateControlBackground(buttonStyleButton, menu == AppearanceMenu.BUTTON_STYLE)
        (accentButton.getChildAt(0).background as GradientDrawable).setColor(host.colorAccent)
        buttonStyleButton.contentDescription = host.getString(
            R.string.ui_choose_button_style, host.getString(host.buttonStyle().labelRes())
        )
    }

    private fun updateControlBackground(button: View, expanded: Boolean) {
        button.isSelected = expanded
        (button.background as GradientDrawable).apply {
            setColor(host.colorSurfaceLight)
            setStroke(
                host.dp(if (expanded) AirUiTokens.Stroke.Selected else AirUiTokens.Stroke.Hairline),
                if (expanded) host.colorAccent else host.colorStroke
            )
        }
    }

    private fun panelGeometry(): AppearancePanelMotion.Geometry {
        val button = if (surfaceMenu == AppearanceMenu.ACCENT) accentButton else buttonStyleButton
        anchorBounds.set(0, 0, button.width, button.height)
        offsetDescendantRectToMyCoords(button, anchorBounds)
        val gap = host.dp(AirUiTokens.Space.Xxl).toFloat()
        val inset = host.dp(AirUiTokens.Stroke.Hairline) / 2f
        return AppearancePanelMotion.Geometry(
            anchorBounds.exactCenterX(), anchorBounds.exactCenterY(),
            button.width.toFloat(), button.height.toFloat(),
            inset, getChildAt(0).bottom + gap + inset,
            (width - inset * 2f).coerceAtLeast(0f),
            (fullPickerHeight - gap - inset * 2f).coerceAtLeast(0f),
            host.dp(AirUiTokens.Radius.Md).toFloat()
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        surface.draw(
            canvas, panelGeometry(), pickerProgress, host.colorCard, host.colorStroke,
            host.dp(AirUiTokens.Stroke.Hairline).toFloat()
        )
    }

    private fun cancelPickerAnimation() {
        pickerAnimator?.removeAllListeners()
        pickerAnimator?.removeAllUpdateListeners()
        pickerAnimator?.cancel()
        pickerAnimator = null
    }

    private fun cancelContentAnimation() {
        contentAnimator?.removeAllListeners()
        contentAnimator?.removeAllUpdateListeners()
        contentAnimator?.cancel()
        contentAnimator = null
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
