package com.andsi.airlyrics.ui.widgets

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class WaterTabHighlightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    constructor(context: Context, accentColor: Int) : this(context, null, 0) {
        setAccentColor(accentColor)
    }

    private var accentColor: Int = Color.TRANSPARENT
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        alpha = 54
    }
    private val rect = RectF()
    private var animator: ValueAnimator? = null

    private var currentLeft = 0f
    private var currentTop = 0f
    private var currentWidth = 0f
    private var currentHeight = 0f
    private var destinationLeft = 0f
    private var destinationTop = 0f
    private var destinationWidth = 0f
    private var destinationHeight = 0f
    private var stretch = 0f
    var hasPosition = false
        private set
    val isAnimating: Boolean
        get() = animator?.isRunning == true

    fun setAccentColor(color: Int) {
        if (accentColor == color) return
        accentColor = color
        paint.color = color
        glowPaint.color = color
        glowPaint.alpha = 54
        invalidate()
    }

    fun moveTo(
        targetCenterX: Float,
        targetCenterY: Float,
        targetWidth: Float,
        targetHeight: Float,
        animate: Boolean
    ) {
        if (targetWidth <= 0f || targetHeight <= 0f) return
        if (width <= 0 || height <= 0) return
        val safeInset = (resources.displayMetrics.density * 8f).coerceAtMost(width / 2f)
        val boundedWidth = targetWidth.coerceAtMost((width - safeInset * 2).coerceAtLeast(0f))
        val boundedHeight = targetHeight.coerceAtMost(height.toFloat())
        val halfWidth = boundedWidth / 2f
        val clampedTargetCenterX = targetCenterX.coerceIn(
            safeInset + halfWidth,
            width - safeInset - halfWidth
        )
        val targetLeft = clampedTargetCenterX - halfWidth
        val targetTop = (targetCenterY - boundedHeight / 2f).coerceIn(0f, height - boundedHeight)

        val sameDestination = abs(destinationLeft - targetLeft) < 0.5f &&
            abs(destinationTop - targetTop) < 0.5f &&
            abs(destinationWidth - boundedWidth) < 0.5f &&
            abs(destinationHeight - boundedHeight) < 0.5f
        if (animate && animator?.isRunning == true && sameDestination) return
        animator?.cancel()
        animator = null
        destinationLeft = targetLeft
        destinationTop = targetTop
        destinationWidth = boundedWidth
        destinationHeight = boundedHeight

        if (!hasPosition || !animate || !ValueAnimator.areAnimatorsEnabled()) {
            currentLeft = targetLeft
            currentTop = targetTop
            currentWidth = boundedWidth
            currentHeight = boundedHeight
            stretch = 0f
            hasPosition = true
            invalidate()
            return
        }

        val startLeft = currentLeft
        val startTop = currentTop
        val startWidth = currentWidth
        val startHeight = currentHeight
        val startCenter = startLeft + startWidth / 2f
        val targetCenter = targetLeft + boundedWidth / 2f
        val travel = abs(targetCenter - startCenter)

        if (sameDestination && travel < 0.5f) return

        // Motion Prompt #057 · MIT License (c) 2026 Shohei Ohtaki · https://showheyohtaki.github.io/motion-prompt/#057
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250L
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val t = animation.animatedValue as Float
                val eased = if (t < 0.5f) 4f * t * t * t
                    else 1f - (2f - 2f * t).let { it * it * it } / 2f
                currentLeft = lerp(startLeft, targetLeft, eased)
                currentTop = lerp(startTop, targetTop, eased)
                currentWidth = lerp(startWidth, boundedWidth, eased)
                currentHeight = lerp(startHeight, boundedHeight, eased)
                stretch = sin((t * PI).toFloat()) * travel * 0.26f
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) animator = null
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasPosition) return

        val centerX = currentLeft + currentWidth / 2f
        val centerY = currentTop + currentHeight / 2f
        val safeInset = resources.displayMetrics.density * 8f
        val wantedHalfWidth = currentWidth / 2f + stretch
        val halfHeight = currentHeight / 2f
        val radius = halfHeight.coerceAtLeast(1f)

        rect.set(
            centerX - wantedHalfWidth,
            centerY - halfHeight,
            centerX + wantedHalfWidth,
            centerY + halfHeight
        )
        if (rect.left < safeInset) {
            rect.offset(safeInset - rect.left, 0f)
        }
        if (rect.right > width - safeInset) {
            rect.offset(width - safeInset - rect.right, 0f)
        }
        canvas.drawRoundRect(rect, radius, radius, glowPaint)

        val inset = resources.displayMetrics.density * 2f
        rect.inset(inset, inset)
        canvas.drawRoundRect(rect, (radius - inset).coerceAtLeast(1f), (radius - inset).coerceAtLeast(1f), paint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private fun lerp(start: Float, end: Float, amount: Float): Float {
        return start + (end - start) * amount
    }
}
