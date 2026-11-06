package com.andsi.airlyrics.app.search

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.andsi.airlyrics.R
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.theme.AirLyricsPalette
import com.google.android.material.button.MaterialButton

/** The compact search prompt uses the same color, type and shape tokens as AirDialogs. */
@Suppress("DEPRECATION")
internal class MissingSourcesDialog(
    context: Context,
    palette: AirLyricsPalette,
    isDark: Boolean,
    private val backgroundDimAmount: Float
) : Dialog(context, android.R.style.Theme_Translucent_NoTitleBar) {
    private val targetDimAmount = maxOf(0.42f, backgroundDimAmount)
    private val systemBarBase = palette.background
    private val card: NestedScrollView
    private var motion: ValueAnimator? = null
    private var amount = 0f
    private var leaving = false

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(AirUiTokens.Space.DialogH), dp(AirUiTokens.Space.DialogTop),
                dp(AirUiTokens.Space.DialogH), dp(AirUiTokens.Space.DialogBottom))
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        body.addView(TextView(context).apply {
            id = android.R.id.message
            setText(R.string.search_no_sources_selected)
            textSize = AirUiTokens.TextSize.DialogTitle
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.textStrong)
        })
        body.addView(MaterialButton(context).apply {
            id = android.R.id.button1
            setText(R.string.ui_ok)
            isAllCaps = false
            textSize = AirUiTokens.TextSize.Button
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0f
            cornerRadius = dp(AirUiTokens.Radius.Md)
            minHeight = dp(48)
            minWidth = dp(96)
            elevation = 0f
            stateListAnimator = null
            backgroundTintList = ColorStateList.valueOf(palette.accent)
            setTextColor(palette.onAccent)
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END
            topMargin = dp(AirUiTokens.Space.CardV)
        })

        card = NestedScrollView(context).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            background = GradientDrawable().apply {
                cornerRadius = dp(AirUiTokens.Radius.Dialog).toFloat()
                setColor(palette.card)
            }
            clipToOutline = true
            elevation = dp(8).toFloat()
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(body)
        }
        val root = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                card.layoutParams.width = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
                    .coerceAtLeast(0).coerceAtMost(dp(420))
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener { cancel() }
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
                view.setPadding(safe.left + dp(12), safe.top + dp(12),
                    safe.right + dp(12), safe.bottom + dp(12))
                insets
            }
            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        setContentView(root)
        window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            WindowCompat.setDecorFitsSystemWindows(this, false)
            clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isStatusBarContrastEnforced = false
                isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(this, decorView).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(backgroundDimAmount)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        setOnShowListener {
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            root.post {
                if (!isShowing || leaving) return@post
                ViewCompat.requestApplyInsets(root)
                if (ValueAnimator.areAnimatorsEnabled()) animateTo(1f, 320L, OPEN_EASE)
                else render(1f)
            }
        }
        render(0f)
    }

    override fun dismiss() {
        if (leaving) return
        if (!isShowing || !ValueAnimator.areAnimatorsEnabled()) {
            dismissImmediately()
            return
        }
        leaving = true
        animateTo(0f, (280L * amount).toLong().coerceAtLeast(1L), CLOSE_EASE) {
            super.dismiss()
        }
    }

    fun dismissImmediately() {
        leaving = true
        val previous = motion
        motion = null
        previous?.cancel()
        super.dismiss()
    }

    // Motion Prompt #049 · MIT License (c) 2026 Shohei Ohtaki · https://showheyohtaki.github.io/motion-prompt/#049
    // Ported to Android Views: the scrim fades to 0.42 while the card grows from 0.9 to 1
    // over 320 ms; dismissal reverses the motion over 280 ms. Colors and shape use AirLyrics tokens.
    private fun animateTo(target: Float, durationMs: Long, easing: TimeInterpolator, onEnd: () -> Unit = {}) {
        val previous = motion
        motion = null
        previous?.cancel()
        motion = ValueAnimator.ofFloat(amount, target).apply {
            duration = durationMs
            interpolator = easing
            addUpdateListener { render(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (motion === animation) {
                        motion = null
                        onEnd()
                    }
                }
            })
            start()
        }
    }

    private fun render(value: Float) {
        amount = value
        card.alpha = value
        card.scaleX = 0.9f + 0.1f * value
        card.scaleY = 0.9f + 0.1f * value
        val dimAmount = backgroundDimAmount + (targetDimAmount - backgroundDimAmount) * value
        window?.setDimAmount(dimAmount)
        val systemBarColor = ColorUtils.blendARGB(systemBarBase, Color.BLACK, dimAmount)
        window?.statusBarColor = systemBarColor
        window?.navigationBarColor = systemBarColor
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    // Motion Prompt #049-072 · MIT License (c) 2026 Shohei Ohtaki · https://showheyohtaki.github.io/motion-prompt/#049
    // The UI kit's cubic easing functions are expressed here as Android interpolators.
    private companion object {
        val OPEN_EASE = TimeInterpolator { t ->
            val remaining = 1f - t
            1f - remaining * remaining * remaining
        }
        val CLOSE_EASE = TimeInterpolator { t ->
            if (t < 0.5f) 4f * t * t * t else {
                val remaining = -2f * t + 2f
                1f - remaining * remaining * remaining / 2f
            }
        }
    }
}
