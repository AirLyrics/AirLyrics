package com.andsi.airlyrics.app.search

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.andsi.airlyrics.design.tokens.AirUiTokens
import com.andsi.airlyrics.ui.theme.AirLyricsPalette
import kotlin.math.abs

/**
 * A spring presents the input surface; a single timed transition closes its card and shade.
 * A separate parent handles IME/layout translation so resizing cannot restart the motion.
 */
@Suppress("DEPRECATION")
internal class LyricsSearchInputDialog(
    context: Context,
    palette: AirLyricsPalette,
    isDark: Boolean,
    content: View,
    private val backgroundDimAmount: Float,
    animateEntry: Boolean
) : Dialog(context, android.R.style.Theme_Translucent_NoTitleBar) {
    private val useEntryMotion = animateEntry && ValueAnimator.areAnimatorsEnabled()
    private val targetDimAmount = maxOf(AirUiTokens.Layout.DialogDimAmount, backgroundDimAmount)
    internal val card = NestedScrollView(context).apply {
        isFillViewport = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        background = GradientDrawable().apply {
            cornerRadius = dp(AirUiTokens.Radius.Dialog).toFloat()
            setColor(palette.card)
            setStroke(dp(AirUiTokens.Stroke.Hairline), palette.stroke)
        }
        clipToOutline = true
        isClickable = true
        addView(content)
    }
    private val positioner = FrameLayout(context).apply { addView(card) }
    private var fade: ValueAnimator? = null
    private var opacity = if (useEntryMotion) 0f else 1f
    private var removed = false
    internal var isLeaving = false
        private set
    private var imeAnimation: WindowInsetsAnimationCompat? = null
    private val trackingIme get() = imeAnimation != null
    private var imeStartTop = 0f
    private var imeDelta = 0f
    private val spring = SpringAnimation(FloatValueHolder(if (useEntryMotion) 0f else 1f)).apply {
        spring = SpringForce(1f).apply {
            stiffness = AirUiTokens.Motion.InputDialogStiffness
            dampingRatio = AirUiTokens.Motion.InputDialogOpenDamping
        }
        setMinimumVisibleChange(0.001f)
        addUpdateListener { _, value, _ -> renderTransform(value) }
    }
    private val easeOut = PathInterpolator(0.23f, 1f, 0.32f, 1f)
    private val root = object : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            positioner.layoutParams.width =
                (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
                    .coerceIn(0, dp(420))
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        root.apply {
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setOnClickListener { cancel() }
            addView(positioner, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        positioner.addOnLayoutChangeListener { _, _, top, _, _, _, oldTop, _, oldBottom ->
            if (top == oldTop || oldBottom == 0 || (trackingIme && !isLeaving)) return@addOnLayoutChangeListener
            positioner.animate().cancel()
            if (!ValueAnimator.areAnimatorsEnabled()) {
                positioner.translationY = 0f
            } else {
                // FLIP: error text and available-height changes keep the current visual position.
                positioner.translationY += oldTop - top
                if (!isLeaving) positioner.animate().translationY(0f)
                    .setDuration(AirUiTokens.Motion.LayoutChangeMs).setInterpolator(easeOut).start()
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            if (!isLeaving) {
                val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
                view.setPadding(safe.left + dp(20), safe.top + dp(24),
                    safe.right + dp(20), safe.bottom + dp(24))
            }
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(root,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() == 0 || isLeaving) return
                    imeAnimation = animation
                    positioner.animate().cancel()
                    imeStartTop = positioner.top + positioner.translationY
                }

                override fun onStart(animation: WindowInsetsAnimationCompat,
                    bounds: WindowInsetsAnimationCompat.BoundsCompat): WindowInsetsAnimationCompat.BoundsCompat {
                    if (animation === imeAnimation && !isLeaving) {
                        imeDelta = imeStartTop - positioner.top
                        positioner.translationY = imeDelta
                    }
                    return bounds
                }

                override fun onProgress(insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>): WindowInsetsCompat {
                    if (!isLeaving) {
                        runningAnimations.firstOrNull { it === imeAnimation }?.let {
                            positioner.translationY = if (ValueAnimator.areAnimatorsEnabled())
                                imeDelta * (1f - it.interpolatedFraction) else 0f
                        }
                    }
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (animation === imeAnimation) {
                        imeAnimation = null
                        if (!isLeaving) positioner.translationY = 0f
                    }
                }
            })
        setContentView(root)
        window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setWindowAnimations(0)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isStatusBarContrastEnforced = false
                isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(this, decorView).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
            setDimAmount(if (useEntryMotion) backgroundDimAmount else targetDimAmount)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        renderTransform(if (useEntryMotion) 0f else 1f)
        renderOpacity(opacity)
        setOnShowListener {
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            root.post {
                if (!isShowing || isLeaving || removed) return@post
                ViewCompat.requestApplyInsets(root)
                if (useEntryMotion && ValueAnimator.areAnimatorsEnabled()) {
                    spring.animateToFinalPosition(1f)
                    fadeTo(1f, AirUiTokens.Motion.InputDialogFadeInMs)
                } else {
                    renderTransform(1f)
                    renderOpacity(1f)
                }
            }
        }
    }

    override fun dismiss() {
        if (isLeaving || removed) return
        if (!isShowing || !ValueAnimator.areAnimatorsEnabled()) {
            dismissImmediately()
            return
        }
        isLeaving = true
        // Capture the presented values before cancelling entry/layout motion.
        // One clock now carries the card and shade through the entire exit.
        val startScale = card.scaleX
        val startTranslation = card.translationY
        val startOpacity = opacity
        stopMotion()
        window?.let { WindowCompat.getInsetsController(it, it.decorView).hide(WindowInsetsCompat.Type.ime()) }
        if (startOpacity < 0.001f) {
            dismissImmediately()
            return
        }
        fade = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (AirUiTokens.Motion.InputDialogExitMs * startOpacity).toLong().coerceAtLeast(1L)
            interpolator = easeOut
            addUpdateListener {
                val progress = it.animatedValue as Float
                val scale = startScale + (AirUiTokens.Motion.InputDialogClosedScale - startScale) * progress
                card.scaleX = scale
                card.scaleY = scale
                card.translationY = startTranslation +
                    (dp(AirUiTokens.Motion.InputDialogTravelDp) - startTranslation) * progress
                renderOpacity(startOpacity * (1f - progress))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (fade !== animation) return
                    fade = null
                    dismissImmediately()
                }
            })
            start()
        }
    }

    /** Lifecycle teardown must never leave an animated window attached to a destroyed Activity. */
    fun dismissImmediately() {
        if (removed) return
        removed = true
        isLeaving = true
        stopMotion()
        super.dismiss()
    }

    override fun onStop() {
        stopMotion()
        super.onStop()
    }

    private fun stopMotion() {
        val previous = fade
        fade = null
        previous?.cancel()
        spring.cancel()
        positioner.animate().cancel()
    }

    private fun fadeTo(target: Float, durationMs: Long, onEnd: () -> Unit = {}) {
        val previous = fade
        fade = null
        previous?.cancel()
        if (abs(opacity - target) < 0.001f) {
            renderOpacity(target)
            onEnd()
            return
        }
        fade = ValueAnimator.ofFloat(opacity, target).apply {
            duration = durationMs
            interpolator = easeOut
            addUpdateListener { renderOpacity(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (fade !== animation) return
                    fade = null
                    onEnd()
                }
            })
            start()
        }
    }

    private fun renderTransform(amount: Float) {
        val scale = AirUiTokens.Motion.InputDialogClosedScale +
            (1f - AirUiTokens.Motion.InputDialogClosedScale) * amount
        card.scaleX = scale
        card.scaleY = scale
        card.translationY = dp(AirUiTokens.Motion.InputDialogTravelDp) * (1f - amount)
    }

    private fun renderOpacity(amount: Float) {
        opacity = amount.coerceIn(0f, 1f)
        card.alpha = opacity
        window?.setDimAmount(backgroundDimAmount + (targetDimAmount - backgroundDimAmount) * opacity)
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
