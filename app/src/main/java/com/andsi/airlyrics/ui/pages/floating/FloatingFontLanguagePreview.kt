package com.andsi.airlyrics.ui.pages.floating

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Layout
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.animation.doOnEnd
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.components.AdaptiveGridLayout
import com.andsi.airlyrics.ui.components.canAnimateFloatingControl
import com.andsi.airlyrics.ui.components.enableSoftPressFeedback
import com.andsi.airlyrics.ui.components.floatingPanelEaseOut
import com.andsi.airlyrics.ui.model.MainUiHost
import com.andsi.airlyrics.ui.theme.bindAppearance
import com.andsi.airlyrics.ui.theme.colorSurfaceLight
import com.andsi.airlyrics.ui.theme.colorTextMuted
import com.andsi.airlyrics.ui.theme.colorTextStrong
import java.util.Locale
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
internal class FloatingFontLanguagePreview(
    private val host: MainUiHost,
    typeface: Typeface,
    initialLanguage: String,
    private val onLanguageClick: (String) -> Unit,
) : LinearLayout(host) {
    private class Language(
        val code: String,
        val sample: TextView,
        val button: TextView,
        val fill: GradientDrawable,
        var selectionAmount: Float = 0f,
    )

    private val languages = listOf(Locale.JAPANESE, Locale.CHINESE, Locale.ENGLISH)
        .map { createLanguage(it, typeface) }
    private var selected = languages.firstOrNull { it.code == initialLanguage } ?: languages.first()
    private var animator: ValueAnimator? = null

    init {
        orientation = VERTICAL
        addView(FrameLayout(host).apply {
            // Invisible specimens participate in measurement, so line wrapping never moves the controls.
            languages.forEach { language ->
                addView(
                    language.sample,
                    FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
                )
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(AdaptiveGridLayout(
            host, 3, host.dp(AirUiTokens.Space.Xl), host.dp(AirUiTokens.Space.Sm),
            fillCells = false, rowGravity = Gravity.CENTER_HORIZONTAL,
        ).apply {
            contentDescription = host.getString(R.string.ui_font_preview_language)
            setPadding(0, 0, 0, host.dp(AirUiTokens.SettingsPanel.ControlGap))
            languages.forEach { addView(it.button) }
        })
        updateSelection()
        settle()
    }

    fun setLanguageButtonsEnabled(enabled: Boolean) {
        languages.forEach { it.button.isEnabled = enabled }
    }

    fun finishMotion() {
        stopAnimation()
        settle()
    }

    override fun onDetachedFromWindow() {
        finishMotion()
        super.onDetachedFromWindow()
    }

    @SuppressLint("WrongConstant")
    private fun createLanguage(locale: Locale, face: Typeface): Language {
        val sampleLocale = if (locale == Locale.CHINESE) Locale.SIMPLIFIED_CHINESE else locale
        val configuration = Configuration(host.resources.configuration).apply { setLocale(sampleLocale) }
        val message = host.createConfigurationContext(configuration).getString(
            if (locale == Locale.JAPANESE) R.string.ui_this_is_a_lyric_preview
            else R.string.ui_this_is_a_lyric_preview_translation,
        )
        val sample = TextView(host).apply {
            text = message
            textLocale = sampleLocale
            gravity = Gravity.CENTER
            textAlignment = TEXT_ALIGNMENT_CENTER
            textSize = 25f
            typeface = face
            minimumHeight = host.dp(122)
            includeFontPadding = false
            breakStrategy = Layout.BREAK_STRATEGY_BALANCED
            letterSpacing = .035f
            setLineSpacing(0f, 1.6f)
            setPadding(host.dp(AirUiTokens.Space.Xl), host.dp(AirUiTokens.SettingsPanel.ControlGap),
                host.dp(AirUiTokens.Space.Xl), host.dp(AirUiTokens.SettingsPanel.ControlGap))
            host.bindAppearance(this) { setTextColor(host.colorTextStrong) }
        }
        val fill = pill(Color.TRANSPARENT)
        val verticalInset = host.dp(AirUiTokens.Space.Xl)
        val ripple = RippleDrawable(
            ColorStateList.valueOf(Color.TRANSPARENT),
            InsetDrawable(fill, 0, verticalInset, 0, verticalInset),
            InsetDrawable(pill(Color.WHITE), 0, verticalInset, 0, verticalInset),
        )
        val button = TextView(host).apply {
            text = locale.getDisplayLanguage(locale)
            textLocale = locale
            textSize = AirUiTokens.TextSize.BodySmall
            gravity = Gravity.CENTER
            includeFontPadding = false
            minimumWidth = host.dp(AirUiTokens.Layout.IconTouchSize)
            minimumHeight = host.dp(AirUiTokens.Layout.IconTouchSize)
            setPaddingRelative(host.dp(AirUiTokens.SettingsPanel.ControlGap),
                host.dp(AirUiTokens.Space.Xxl), host.dp(AirUiTokens.SettingsPanel.ControlGap),
                host.dp(AirUiTokens.Space.Xxl))
            isFocusable = true
            background = ripple
            setTag(R.id.interaction_anchor, "floating.font.language.${locale.language}")
            enableSoftPressFeedback(AirUiTokens.Motion.OptionPressScale)
        }
        return Language(locale.language, sample, button, fill).also { language ->
            host.bindAppearance(button) {
                ripple.setColor(ColorStateList.valueOf(ColorUtils.setAlphaComponent(host.colorTextStrong, 24)))
                renderButton(language)
            }
            button.setOnClickListener { select(language) }
        }
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        cornerRadius = host.dp(AirUiTokens.Radius.Pill).toFloat()
        setColor(color)
    }

    private fun select(language: Language) {
        if (!language.button.isEnabled) return
        onLanguageClick(language.code)
        if (language === selected) return
        stopAnimation()
        selected = language
        updateSelection()
        if (!language.button.isLaidOut || !language.button.canAnimateFloatingControl()) {
            settle()
            return
        }

        val incoming = language.sample
        // If a third language is picked mid-fade, retain the most visible outgoing specimen.
        val outgoing = languages.asSequence().map { it.sample }
            .filter { (it !== incoming) && (it.alpha > 0f) }
            .maxByOrNull { it.alpha }
        languages.forEach { option ->
            if (option.sample !== incoming && option.sample !== outgoing) {
                resetSample(option.sample, visible = false)
            }
        }
        val travel = host.dp(AirUiTokens.Space.Lg).toFloat()
        val incomingAlpha = incoming.alpha
        val incomingY = if (incoming.isVisible) incoming.translationY else travel
        val outgoingAlpha = outgoing?.alpha ?: 0f
        val outgoingY = outgoing?.translationY ?: 0f
        incoming.translationY = incomingY
        incoming.visibility = VISIBLE
        val amounts = languages.map { it.selectionAmount }

        val motion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = AirUiTokens.Motion.LayoutChangeMs
            interpolator = floatingPanelEaseOut
            addUpdateListener {
                val progress = it.animatedValue as Float
                val remaining = 1f - progress
                incoming.alpha = incomingAlpha + (1f - incomingAlpha) * progress
                incoming.translationY = incomingY * remaining
                outgoing?.apply {
                    alpha = outgoingAlpha * remaining
                    translationY = outgoingY * remaining - travel * progress
                }
                languages.forEachIndexed { index, option ->
                    option.selectionAmount = amounts[index] * remaining +
                        if (option === selected) progress else 0f
                    renderButton(option)
                }
            }
            doOnEnd {
                if (animator === it) {
                    animator = null
                    settle()
                }
            }
        }
        animator = motion
        motion.start()
    }

    private fun updateSelection() {
        languages.forEach { language ->
            val active = language === selected
            language.button.isSelected = active
            language.sample.importantForAccessibility =
                if (active) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO
            language.sample.setTag(R.id.interaction_anchor, if (active) "floating.font.sample" else null)
        }
    }

    private fun stopAnimation() {
        val running = animator
        animator = null
        running?.cancel()
    }

    private fun settle() {
        languages.forEach { language ->
            val active = language === selected
            resetSample(language.sample, active)
            language.selectionAmount = if (active) 1f else 0f
            renderButton(language)
        }
    }

    private fun resetSample(sample: TextView, visible: Boolean) {
        sample.visibility = if (visible) VISIBLE else INVISIBLE
        sample.alpha = if (visible) 1f else 0f
        sample.translationY = 0f
    }

    private fun renderButton(language: Language) {
        val amount = language.selectionAmount
        val tint = ColorUtils.blendARGB(host.colorSurfaceLight, host.colorTextStrong, .06f)
        language.fill.setColor(ColorUtils.setAlphaComponent(tint, (255 * amount).roundToInt()))
        language.button.setTextColor(ColorUtils.blendARGB(host.colorTextMuted, host.colorTextStrong, amount))
    }
}
